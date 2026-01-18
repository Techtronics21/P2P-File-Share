package org.arnavthakur.utils;

import java.util.Random;

public class UploadUtils {
    public static int generatePort(){
        int DYNAMIC_STARTING_PORT = 49152;
        int DYNAMIC_ENDING_PORT = 65535;
        int range = (DYNAMIC_ENDING_PORT - DYNAMIC_STARTING_PORT) + 1; // inclusive
        Random random = new Random();
        return DYNAMIC_STARTING_PORT + random.nextInt(range);
    }
}
