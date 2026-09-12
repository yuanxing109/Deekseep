package com.dsmod.probe;

import android.content.Context;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 基于 DexKit 的宿主符号运行时解析器。
 *
 * <p>DeepSeek 每次发版都会被 R8 重新混淆，类名从 2.2.x 的短名一路变到 2.4.5 的
 * 三字符混合名。{@link HostCompat} 用一张手工维护的映射表来跟进，代价是每出一个
 * 新版本就要重新抓一份对照表。</p>
 *
 * <p>这个解析器换一条路：不认名字，只认行为。它把每个目标类的可观测契约写成
 * 匹配条件（方法参数个数/类型、返回类型、构造器形状），交给 DexKit 扫一遍宿主
 * APK 的 DEX，直接把当前版本的真实类名解出来。名字再怎么变，只要契约没动，
 * 就还能找到。</p>
 *
 * <p>解析结果按宿主 versionCode 缓存到宿主应用自己的 cache 目录，命中缓存时
 * 不做任何扫描，冷启动开销只有一次文件读取。</p>
 */
final class DexKitHostResolver {

    private static final String TAG = "Deekseep/DexKit";

    // 逻辑键沿用 HostCompat 的 2.2.x 符号名，两边可以直接对接。
    static final String KEY_CHAT_VIEW_MODEL    = "td1"; // ChatViewModel
    static final String KEY_CHAT_STATE_EVENT   = "lq";  // Chat State / Event
    static final String KEY_COMPLETION_REQUEST = "nx0"; // ChatCompletionRequest (CN)
    static final String KEY_FULL_REQUEST       = "qw0"; // ChatFullCompletionRequest
    static final String KEY_VIEW_MODEL_OWNER   = "sc";  // LocalViewModelStoreOwner

    private static final Object LOCK = new Object();
    private static volatile Map<String, String> resolved;
    private static volatile boolean attempted;

    static {
        // DexKit 的静态初始化也会加载，重复加载同一原生库是安全的。
        // 这里提前加载一次，便于把失败原因打出来。
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            Log.w(TAG, "预加载 dexkit 原生库失败（稍后由 DexKit 自行加载）: " + t);
        }
    }

    private DexKitHostResolver() {}

    /** 已经解析出的映射快照，未解析时返回空表。 */
    static Map<String, String> snapshot() {
        Map<String, String> local = resolved;
        return local == null ? Collections.<String, String>emptyMap() : local;
    }

    /** 按逻辑键取真实类名，取不到返回 null。 */
    static String lookup(String logicalKey) {
        Map<String, String> local = resolved;
        return local == null ? null : local.get(logicalKey);
    }

    static boolean isReady() {
        Map<String, String> local = resolved;
        return attempted && local != null && !local.isEmpty();
    }

    /**
     * 解析宿主符号。整个进程只会真正跑一次，之后直接命中内存缓存。
     *
     * @param hostContext 宿主应用上下文，用于定位缓存目录
     * @param apkPath     宿主 APK 路径（ApplicationInfo.sourceDir）
     * @param versionCode 宿主 versionCode，作为缓存分代键
     * @return 逻辑键 → 真实类名 的映射（可能为空）
     */
    static Map<String, String> resolve(Context hostContext, String apkPath, long versionCode) {
        synchronized (LOCK) {
            if (attempted) {
                Map<String, String> local = resolved;
                return local == null ? Collections.<String, String>emptyMap() : local;
            }
            attempted = true;

            if (apkPath == null || apkPath.isEmpty()) {
                Log.w(TAG, "宿主 APK 路径为空，跳过解析");
                return Collections.emptyMap();
            }

            Map<String, String> cached = readCache(hostContext, versionCode);
            if (cached != null && !cached.isEmpty()) {
                resolved = cached;
                Log.i(TAG, "命中磁盘缓存，共 " + cached.size() + " 条映射");
                logMap(cached);
                return cached;
            }

            Map<String, String> fresh = scan(apkPath);
            if (!fresh.isEmpty()) {
                resolved = fresh;
                writeCache(hostContext, versionCode, fresh);
                logMap(fresh);
            } else {
                Log.w(TAG, "本次解析没有命中任何目标类");
            }
            return fresh;
        }
    }

    // ---------------------------------------------------------------- 扫描

    private static Map<String, String> scan(String apkPath) {
        Map<String, String> out = new LinkedHashMap<>();
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(apkPath);
        } catch (Throwable t) {
            Log.w(TAG, "DexKitBridge 创建失败: " + t);
            return out;
        }
        try {
            String viewModel = findChatViewModel(bridge, out);
            findChatStateEvent(bridge, out, viewModel);
            findCompletionRequests(bridge, out);
            findViewModelStoreOwner(bridge, out);
        } catch (Throwable t) {
            Log.w(TAG, "扫描过程异常: " + t);
        } finally {
            try {
                bridge.close();
            } catch (Throwable ignored) {
                // close 失败不影响结果
            }
        }
        return out;
    }

    /**
     * ChatViewModel：同时具备 {@code A(Object) -> Object} 与
     * {@code f(Object, Object, Object) -> Object} 两个方法。
     * 这是 2.4.5 主 reducer 的签名特征。
     */
    private static String findChatViewModel(DexKitBridge bridge, Map<String, String> out) {
        List<MethodData> candidates = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .paramCount(3)
                        .returnType("java.lang.Object")));

        for (MethodData method : candidates) {
            ClassData owner = method.getDeclaredClass();
            if (owner == null) continue;
            if (hasMethod(owner, "A", 1, new String[]{"java.lang.Object"})
                    && hasMethod(owner, "f", 3, new String[]{
                            "java.lang.Object", "java.lang.Object", "java.lang.Object"})) {
                String name = owner.getName();
                out.put(KEY_CHAT_VIEW_MODEL, name);
                Log.i(TAG, "ChatViewModel -> " + name);
                return name;
            }
        }
        Log.w(TAG, "未定位到 ChatViewModel");
        return null;
    }

    /**
     * Chat State / Event：从 ChatViewModel 的无参方法里挑返回类型。
     * 优先取同时出现在「两参 void 方法」参数表里的那个类型，
     * 因为 reducer 的入参正是 state 与 event。
     */
    private static void findChatStateEvent(DexKitBridge bridge,
                                           Map<String, String> out,
                                           String viewModelName) {
        if (viewModelName == null) return;
        try {
            ClassData viewModel = findClassByName(bridge, viewModelName);
            if (viewModel == null) return;

            List<MethodData> methods = viewModel.getMethods();
            if (methods == null) return;

            // 收集两参 void 方法的参数类型，作为 state/event 的候选池。
            Map<String, Integer> paramPool = new HashMap<>();
            for (MethodData md : methods) {
                List<String> params = md.getParamTypeNames();
                if (params != null && params.size() == 2 && "void".equals(md.getReturnTypeName())) {
                    for (String p : params) {
                        Integer n = paramPool.get(p);
                        paramPool.put(p, n == null ? 1 : n + 1);
                    }
                }
            }

            String best = null;
            int bestScore = 0;
            for (MethodData md : methods) {
                List<String> params = md.getParamTypeNames();
                if (params != null && !params.isEmpty()) continue;
                String ret = md.getReturnTypeName();
                if (ret == null || ret.startsWith("java.") || ret.startsWith("kotlin.")) continue;
                if (ret.equals(viewModelName)) continue;
                Integer score = paramPool.get(ret);
                int s = score == null ? 1 : score + 1;
                if (s > bestScore) {
                    bestScore = s;
                    best = ret;
                }
            }

            if (best != null) {
                out.put(KEY_CHAT_STATE_EVENT, best);
                Log.i(TAG, "Chat State/Event -> " + best);
            } else {
                Log.w(TAG, "未定位到 Chat State/Event");
            }
        } catch (Throwable t) {
            Log.w(TAG, "定位 Chat State/Event 失败: " + t);
        }
    }

    /**
     * Completion Request 家族。
     *
     * <p>2.3 之后的请求类共用一个 11 参构造器形状：
     * {@code (String, ?, String, ?, boolean, boolean, ?, boolean, String, String, int)}。
     * 其中带无参 {@code serializer()} 的是 ChatCompletionRequest。</p>
     *
     * <p>ChatFullCompletionRequest 与它形状完全一致，仅凭结构无法区分，
     * 这里把命中的候选按类名排序，第一个记为 CompletionRequest，
     * 若存在第二个不同类则记为 FullRequest，并把全部候选打进日志供人工核对。</p>
     */
    private static void findCompletionRequests(DexKitBridge bridge, Map<String, String> out) {
        List<MethodData> serializers = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().name("serializer").paramCount(0)));

        Map<String, ClassData> hits = new TreeMap<>();
        for (MethodData md : serializers) {
            ClassData owner = md.getDeclaredClass();
            if (owner == null) continue;
            if (hasRequestConstructor(owner)) {
                hits.put(owner.getName(), owner);
            }
        }

        if (hits.isEmpty()) {
            Log.w(TAG, "未定位到 Completion Request 家族");
            return;
        }

        int index = 0;
        for (Map.Entry<String, ClassData> entry : hits.entrySet()) {
            String name = entry.getKey();
            if (index == 0) {
                out.put(KEY_COMPLETION_REQUEST, name);
                Log.i(TAG, "ChatCompletionRequest -> " + name);
            } else if (index == 1) {
                out.put(KEY_FULL_REQUEST, name);
                Log.i(TAG, "ChatFullCompletionRequest -> " + name);
            } else {
                Log.i(TAG, "请求类候选（未归类）-> " + name);
            }
            index++;
        }
    }

    /** 匹配 {@code serializer()} 所在类的 11 参构造器形状。 */
    private static boolean hasRequestConstructor(ClassData owner) {
        List<MethodData> methods = owner.getMethods();
        if (methods == null) return false;
        for (MethodData md : methods) {
            if (!"<init>".equals(md.getName())) continue;
            List<String> p = md.getParamTypeNames();
            if (p == null || p.size() != 11) continue;
            if ("java.lang.String".equals(p.get(0))
                    && "java.lang.String".equals(p.get(2))
                    && "boolean".equals(p.get(4))
                    && "boolean".equals(p.get(5))
                    && "boolean".equals(p.get(7))
                    && "java.lang.String".equals(p.get(8))
                    && "java.lang.String".equals(p.get(9))
                    && "int".equals(p.get(10))) {
                return true;
            }
        }
        return false;
    }

    /**
     * LocalViewModelStoreOwner：找出返回 {@code androidx.lifecycle.ViewModelStoreOwner}
     * 的无参方法，其宿主类即为提供者。
     */
    private static void findViewModelStoreOwner(DexKitBridge bridge, Map<String, String> out) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(0)
                            .returnType("androidx.lifecycle.ViewModelStoreOwner")));
            for (MethodData md : methods) {
                ClassData owner = md.getDeclaredClass();
                if (owner == null) continue;
                String name = owner.getName();
                if (name.startsWith("androidx.")) continue;
                out.put(KEY_VIEW_MODEL_OWNER, name);
                Log.i(TAG, "LocalViewModelStoreOwner -> " + name);
                return;
            }
            Log.w(TAG, "未定位到 LocalViewModelStoreOwner");
        } catch (Throwable t) {
            Log.w(TAG, "定位 LocalViewModelStoreOwner 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static ClassData findClassByName(DexKitBridge bridge, String className) {
        try {
            List<ClassData> list = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().className(className)));
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Throwable t) {
            Log.w(TAG, "按类名查找失败 " + className + ": " + t);
        }
        return null;
    }

    private static boolean hasMethod(ClassData owner, String name, int paramCount, String[] params) {
        List<MethodData> methods = owner.getMethods();
        if (methods == null) return false;
        for (MethodData md : methods) {
            if (!name.equals(md.getName())) continue;
            List<String> types = md.getParamTypeNames();
            if (types == null || types.size() != paramCount) continue;
            boolean same = true;
            for (int i = 0; i < paramCount; i++) {
                if (!params[i].equals(types.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static void logMap(Map<String, String> map) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            Log.i(TAG, "  " + e.getKey() + " = " + e.getValue());
        }
    }

    // ---------------------------------------------------------------- 缓存

    private static File cacheFile(Context context, long versionCode) {
        if (context == null) return null;
        File dir = context.getCacheDir();
        if (dir == null) return null;
        return new File(dir, "dexkit_hostmap_" + versionCode + ".properties");
    }

    private static Map<String, String> readCache(Context context, long versionCode) {
        File file = cacheFile(context, versionCode);
        if (file == null || !file.isFile()) return null;
        FileInputStream in = null;
        try {
            Properties props = new Properties();
            in = new FileInputStream(file);
            props.load(in);
            Map<String, String> map = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
            return map;
        } catch (Throwable t) {
            Log.w(TAG, "读取缓存失败: " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeCache(Context context, long versionCode, Map<String, String> map) {
        File file = cacheFile(context, versionCode);
        if (file == null) return;
        FileOutputStream out = null;
        try {
            Properties props = new Properties();
            for (Map.Entry<String, String> e : map.entrySet()) {
                props.setProperty(e.getKey(), e.getValue());
            }
            out = new FileOutputStream(file);
            props.store(out, "Deekseep DexKit host map v" + versionCode);
        } catch (Throwable t) {
            Log.w(TAG, "写入缓存失败: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
