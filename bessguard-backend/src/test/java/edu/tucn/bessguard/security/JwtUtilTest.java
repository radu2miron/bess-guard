package edu.tucn.bessguard.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    @Test
    void generatedTokenContainsUsernameAndValidSignature() {
        byte[] key = new byte[64];
        java.util.Arrays.fill(key, (byte) 9);
        JwtUtil util = new JwtUtil(Base64.getEncoder().encodeToString(key));
        String token = util.generateToken("ems");

        assertTrue(util.validateToken(token));
        assertEquals("ems", util.getUsernameFromToken(token));
        assertFalse(util.validateToken(token + "invalid"));
    }

    @Test
    void rejectsShortHS512Key() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtUtil(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
