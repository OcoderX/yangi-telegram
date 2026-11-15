package org.telegram.messenger.ayu.hidden;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password derivation and immutable primitive-key membership, independent of Android. */
final class HiddenChatSecurity {
    static final int ITERATIONS = 600_000;
    static final int SALT_BYTES = 32;
    static final int HASH_BYTES = 32;

    static byte[] salt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, HASH_BYTES * 8);
        try {
            // Available on every supported Android version, including API 23.
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static boolean matches(char[] password, byte[] salt, byte[] expected) throws GeneralSecurityException {
        byte[] actual = derive(password, salt);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    static boolean validPassword(char[] password) {
        return password != null && password.length >= 4 && password.length <= 256;
    }

    static long delayMillis(int failures) {
        if (failures < 3) return 0;
        return Math.min(3_600_000L, 5_000L << Math.min(10, failures - 3));
    }

    /** Immutable open-addressed set: no boxing, locks or allocation on a lookup. */
    static final class IdSet {
        static final IdSet EMPTY = new IdSet(new long[0]);
        private final long[] table;
        final long[] ids;

        IdSet(long[] source) {
            ids = source.clone();
            int capacity = 2;
            while (capacity < source.length * 2) capacity <<= 1;
            table = new long[capacity];
            for (long id : source) {
                if (id == 0) throw new IllegalArgumentException("Invalid dialog id");
                int index = index(id);
                while (table[index] != 0 && table[index] != id) index = (index + 1) & (table.length - 1);
                if (table[index] == id) throw new IllegalArgumentException("Duplicate dialog id");
                table[index] = id;
            }
        }

        private int index(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            return ((int) value) & (table.length - 1);
        }

        boolean contains(long id) {
            if (id == 0) return false;
            int index = index(id);
            while (table[index] != 0) {
                if (table[index] == id) return true;
                index = (index + 1) & (table.length - 1);
            }
            return false;
        }
    }
}
