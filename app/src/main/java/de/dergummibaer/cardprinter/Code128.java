package de.dergummibaer.cardprinter;

/** Minimal Code 128 encoder (subsets B and C with automatic switching). */
public final class Code128 {
    private static final int[] P = {
        0x6CC, 0x66C, 0x666, 0x498, 0x48C, 0x44C, 0x4C8, 0x4C4, 0x464, 0x648, 0x644, 0x624,
        0x59C, 0x4DC, 0x4CE, 0x5CC, 0x4EC, 0x4E6, 0x672, 0x65C, 0x64E, 0x6E4, 0x674, 0x76E,
        0x74C, 0x72C, 0x726, 0x764, 0x734, 0x732, 0x6D8, 0x6C6, 0x636, 0x518, 0x458, 0x446,
        0x588, 0x468, 0x462, 0x688, 0x628, 0x622, 0x5B8, 0x58E, 0x46E, 0x5D8, 0x5C6, 0x476,
        0x776, 0x68E, 0x62E, 0x6E8, 0x6E2, 0x6EE, 0x758, 0x746, 0x716, 0x768, 0x762, 0x71A,
        0x77A, 0x642, 0x78A, 0x530, 0x50C, 0x4B0, 0x486, 0x42C, 0x426, 0x590, 0x584, 0x4D0,
        0x4C2, 0x434, 0x432, 0x612, 0x650, 0x7BA, 0x614, 0x47A, 0x53C, 0x4BC, 0x49E, 0x5E4,
        0x4F4, 0x4F2, 0x7A4, 0x794, 0x792, 0x6DE, 0x6F6, 0x7B6, 0x578, 0x51E, 0x45E, 0x5E8,
        0x5E2, 0x7A8, 0x7A2, 0x5DE, 0x5EE, 0x75E, 0x7AE, 0x684, 0x690, 0x69C };
    private static final int STOP = 0x18EB;

    private Code128() {}

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    private static int run(String s, int i) {
        int n = 0;
        while (i + n < s.length() && digit(s.charAt(i + n))) n++;
        return n;
    }

    /** Value of a non-digit char in set A (mode 0) or B (mode 1); char must be representable. */
    private static int valAB(char c, int mode) {
        if (mode == 1) return c - 32;              // set B: 32..126 -> 0..94
        return (c < 32) ? c + 64 : c - 32;         // set A: ctrl 0..31 -> 64..95, 32..95 -> 0..63
    }

    /**
     * Returns the bar modules (true = bar) without quiet zones, or null if unsupported.
     * Supports Code 128 sets A, B and C, so control characters such as Tab (0x09)
     * and Enter (0x0D) can be encoded, not just printable ASCII.
     */
    public static boolean[] encode(String s) {
        int len = s.length();
        if (len == 0) return null;
        for (int k = 0; k < len; k++) if (s.charAt(k) > 126) return null;  // ASCII 0..126 only
        int[] v = new int[len * 2 + 4];
        int n = 0, i = 0;

        // Pick the start set. mode: 0=A, 1=B, 2=C.
        int startDigits = run(s, 0);
        int mode;
        if (startDigits >= 4 || (startDigits == len && len >= 2 && len % 2 == 0)) {
            mode = 2; v[n++] = 105;                // Start C
        } else if (s.charAt(0) < 32) {
            mode = 0; v[n++] = 103;                // Start A (first char is a control code)
        } else {
            mode = 1; v[n++] = 104;                // Start B
        }

        while (i < len) {
            if (mode == 2) {                       // ---- set C ----
                if (i + 1 < len && digit(s.charAt(i)) && digit(s.charAt(i + 1))) {
                    v[n++] = (s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0');
                    i += 2;
                } else if (s.charAt(i) < 32) {
                    v[n++] = 101; mode = 0;        // -> Code A for a control char
                } else {
                    v[n++] = 100; mode = 1;        // -> Code B
                }
            } else {                               // ---- set A or B ----
                int r = run(s, i);
                if (r >= 6 || (r >= 4 && i + r == len)) {
                    if (r % 2 == 1) { v[n++] = valAB(s.charAt(i), mode); i++; }
                    v[n++] = 99; mode = 2;         // -> Code C
                } else {
                    char c = s.charAt(i);
                    if (mode == 1) {               // set B holds 32..126
                        if (c >= 32) { v[n++] = c - 32; i++; }
                        else { v[n++] = 101; mode = 0; }   // control -> switch to A
                    } else {                       // set A holds 0..95
                        if (c <= 95) { v[n++] = valAB(c, 0); i++; }
                        else { v[n++] = 100; mode = 1; }   // 96..126 -> switch to B
                    }
                }
            }
        }
        long sum = v[0];
        for (int k = 1; k < n; k++) sum += (long) v[k] * k;
        v[n++] = (int) (sum % 103);
        boolean[] m = new boolean[n * 11 + 13];
        int p = 0;
        for (int k = 0; k < n; k++) for (int b = 10; b >= 0; b--) m[p++] = ((P[v[k]] >> b) & 1) == 1;
        for (int b = 12; b >= 0; b--) m[p++] = ((STOP >> b) & 1) == 1;
        return m;
    }
}
