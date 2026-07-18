package com.viaro.utils;

import java.util.Random;

public class RoomCodeGenerator {
    public static String generateRoomCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000); // 6-digit number
        return String.valueOf(code);
    }
}
