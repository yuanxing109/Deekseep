package com.dsmod.probe;

import android.graphics.Path;

/**
 * Minimal SVG path data parser (replaces androidx.core.graphics.PathParser).
 * Supports M, L, H, V, C, S, Q, T, A, Z and their lowercase relative variants.
 */
final class PathParser {
    private PathParser() {}

    static Path createPathFromPathData(String pathData) {
        if (pathData == null || pathData.isEmpty()) return null;
        Path path = new Path();
        int len = pathData.length();
        int i = 0;
        float curX = 0, curY = 0;
        float startX = 0, startY = 0;
        float lastX = 0, lastY = 0;
        char prevCmd = 0;

        while (i < len) {
            // skip whitespace and commas
            while (i < len && (pathData.charAt(i) == ' ' || pathData.charAt(i) == ','
                    || pathData.charAt(i) == '\n' || pathData.charAt(i) == '\r'
                    || pathData.charAt(i) == '\t')) i++;
            if (i >= len) break;

            char cmd = pathData.charAt(i);
            if (Character.isLetter(cmd)) {
                i++;
            } else {
                // implicit repeat of previous command
                if (prevCmd == 'M' || prevCmd == 'm' || prevCmd == 'L' || prevCmd == 'l'
                        || prevCmd == 'T' || prevCmd == 't') {
                    // implicit lineto
                    cmd = (prevCmd == 'M' || prevCmd == 'L' || prevCmd == 'T')
                            ? 'L' : 'l';
                } else if (prevCmd == 'H' || prevCmd == 'h') {
                    cmd = (prevCmd == 'H') ? 'H' : 'h';
                } else if (prevCmd == 'V' || prevCmd == 'v') {
                    cmd = (prevCmd == 'V') ? 'V' : 'v';
                } else if (prevCmd == 'C' || prevCmd == 'c') {
                    cmd = (prevCmd == 'C') ? 'C' : 'c';
                } else if (prevCmd == 'S' || prevCmd == 's') {
                    cmd = (prevCmd == 'S') ? 'S' : 's';
                } else if (prevCmd == 'Q' || prevCmd == 'q') {
                    cmd = (prevCmd == 'Q') ? 'Q' : 'q';
                } else if (prevCmd == 'A' || prevCmd == 'a') {
                    cmd = (prevCmd == 'A') ? 'A' : 'a';
                } else {
                    break;
                }
            }

            boolean relative = Character.isLowerCase(cmd);
            char upperCmd = Character.toUpperCase(cmd);

            switch (upperCmd) {
                case 'M': {
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x += curX; y += curY; }
                    path.moveTo(x, y);
                    curX = x; curY = y;
                    startX = x; startY = y;
                    break;
                }
                case 'L': {
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x += curX; y += curY; }
                    path.lineTo(x, y);
                    curX = x; curY = y;
                    break;
                }
                case 'H': {
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) x += curX;
                    path.lineTo(x, curY);
                    curX = x;
                    break;
                }
                case 'V': {
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) y += curY;
                    path.lineTo(curX, y);
                    curY = y;
                    break;
                }
                case 'C': {
                    float x1 = readNum(pathData, i); i = skipNum(pathData, i);
                    float y1 = readNum(pathData, i); i = skipNum(pathData, i);
                    float x2 = readNum(pathData, i); i = skipNum(pathData, i);
                    float y2 = readNum(pathData, i); i = skipNum(pathData, i);
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x1 += curX; y1 += curY; x2 += curX; y2 += curY; x += curX; y += curY; }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastX = x2; lastY = y2;
                    curX = x; curY = y;
                    break;
                }
                case 'S': {
                    float x2 = readNum(pathData, i); i = skipNum(pathData, i);
                    float y2 = readNum(pathData, i); i = skipNum(pathData, i);
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x2 += curX; y2 += curY; x += curX; y += curY; }
                    float x1 = 2 * curX - lastX;
                    float y1 = 2 * curY - lastY;
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastX = x2; lastY = y2;
                    curX = x; curY = y;
                    break;
                }
                case 'Q': {
                    float x1 = readNum(pathData, i); i = skipNum(pathData, i);
                    float y1 = readNum(pathData, i); i = skipNum(pathData, i);
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x1 += curX; y1 += curY; x += curX; y += curY; }
                    path.quadTo(x1, y1, x, y);
                    lastX = x1; lastY = y1;
                    curX = x; curY = y;
                    break;
                }
                case 'T': {
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x += curX; y += curY; }
                    float x1 = 2 * curX - lastX;
                    float y1 = 2 * curY - lastY;
                    path.quadTo(x1, y1, x, y);
                    lastX = x1; lastY = y1;
                    curX = x; curY = y;
                    break;
                }
                case 'A': {
                    float rx = readNum(pathData, i); i = skipNum(pathData, i);
                    float ry = readNum(pathData, i); i = skipNum(pathData, i);
                    float angle = readNum(pathData, i); i = skipNum(pathData, i);
                    i = skipNum(pathData, i); // large-arc-flag
                    i = skipNum(pathData, i); // sweep-flag
                    float x = readNum(pathData, i); i = skipNum(pathData, i);
                    float y = readNum(pathData, i); i = skipNum(pathData, i);
                    if (relative) { x += curX; y += curY; }
                    path.lineTo(x, y); // simplified: approximate arc as line
                    curX = x; curY = y;
                    break;
                }
                case 'Z': {
                    path.close();
                    curX = startX; curY = startY;
                    break;
                }
                default:
                    break;
            }
            prevCmd = cmd;
        }
        return path;
    }

    private static float readNum(String s, int start) {
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                end++;
            } else {
                break;
            }
        }
        if (end == start) return 0f;
        try {
            return Float.parseFloat(s.substring(start, end));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static int skipNum(String s, int start) {
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                end++;
            } else {
                break;
            }
        }
        // skip trailing separator
        while (end < s.length() && (s.charAt(end) == ' ' || s.charAt(end) == ','
                || s.charAt(end) == '\n' || s.charAt(end) == '\r')) end++;
        return end;
    }
}