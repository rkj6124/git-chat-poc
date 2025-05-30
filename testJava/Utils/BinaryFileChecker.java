package Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

public class BinaryFileChecker {

    // control the maximum number of bytes read in each chunk
    public static int MAX_BYTES = 4 * 1024;

    public BinaryFileChecker() {
        // default constructor
    }

    public static boolean isFilePathString(String filePath) {
        return filePath instanceof String;
    }

    /**
     * Checks if the given file, specified by its path,
     * is likely to be a binary file.
     * 
     * This method performs analysis based on available information,
     * patterns or thumb rules on the file's content to determine
     * whether it contains binary data.
     *
     * @param filePath The path to the file to be checked.
     * @return True if the file is likely binary, false otherwise.
     */
    public static boolean isBinaryFile(File file) {

        FileInputStream inputStream = null;
        int suspiciousBytes = 0;

        try {

            inputStream = new FileInputStream(file);

            long fileLength = Math.min(file.length(), MAX_BYTES);
            byte[] fileBytes = new byte[(int) fileLength];
            int bytesRead = inputStream.read(fileBytes, 0, fileBytes.length);

            if (bytesRead == 0) {
                return false;
            }

            if (bytesRead >= 3 && fileBytes[0] == (byte) 0xef && fileBytes[1] == (byte) 0xbb
                    && fileBytes[2] == (byte) 0xbf) {
                return false;
            }

            if (bytesRead >= 4 && fileBytes[0] == 0x00 && fileBytes[1] == 0x00 && fileBytes[2] == (byte) 0xfe
                    && fileBytes[3] == (byte) 0xff) {
                return false;
            }

            if (bytesRead >= 4 && fileBytes[0] == (byte) 0xff && fileBytes[1] == (byte) 0xfe && fileBytes[2] == 0x00
                    && fileBytes[3] == 0x00) {
                return false;
            }

            if (bytesRead >= 4 && fileBytes[0] == (byte) 0x84 && fileBytes[1] == 0x31 && fileBytes[2] == (byte) 0x95
                    && fileBytes[3] == 0x33) {
                return false;
            }

            if (bytesRead >= 5 && Arrays.equals(Arrays.copyOfRange(fileBytes, 0, 5), "%PDF-".getBytes())) {
                /* PDF. This is binary. */
                return true;
            }

            if (bytesRead >= 2 && fileBytes[0] == (byte) 0xfe && fileBytes[1] == (byte) 0xff) {
                return false;
            }

            if (bytesRead >= 2 && fileBytes[0] == (byte) 0xff && fileBytes[1] == (byte) 0xfe) {
                return false;
            }

            for (int i = 0; i < bytesRead; i++) {
                if (fileBytes[i] == 0) {
                    // NULL byte--it's binary!
                    return true;
                } else if ((fileBytes[i] < 7 || fileBytes[i] > 14) && (fileBytes[i] < 32 || fileBytes[i] > 127)) {
                    // UTF-8 detection
                    if (fileBytes[i] > (byte) 193 && fileBytes[i] < (byte) 224 && i + 1 < bytesRead) {
                        i++;
                        if (fileBytes[i] > (byte) 127 && fileBytes[i] < (byte) 192) {
                            continue;
                        }
                    } else if (fileBytes[i] > (byte) 223 && fileBytes[i] < (byte) 240 && i + 2 < bytesRead) {
                        i++;
                        if (fileBytes[i] > (byte) 127 && fileBytes[i] < (byte) 192 && fileBytes[i + 1] > (byte) 127
                                && fileBytes[i + 1] < (byte) 192) {
                            i++;
                            continue;
                        }
                    }

                    suspiciousBytes++;
                    // Read at least 32 bytes before making a decision
                    if (i >= 32 && (suspiciousBytes * 100) / bytesRead > 10) {
                        return true;
                    }
                }
            }

            if ((suspiciousBytes * 100) / bytesRead > 10) {
                return true;
            }

            // Add additional checks if needed
            return false;

        } catch (Exception ex) {
            System.out.println("exception caught to check file is a binary file or not " + ex);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            }
        }
        return false;
    }
}
