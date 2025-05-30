package co.bito.intellij.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public class MachineIdUtils {
    public enum OSType {
        Windows, MacOS, Linux, Other
    }

    private static OSType detectedOS;

    public static OSType getOperatingSystemType() {
        if (detectedOS == null) {
            String OS = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
            if ((OS.contains("mac")) || (OS.contains("darwin"))) {
                detectedOS = OSType.MacOS;
            } else if (OS.contains("win")) {
                detectedOS = OSType.Windows;
            } else if (OS.contains("nux")) {
                detectedOS = OSType.Linux;
            } else {
                detectedOS = OSType.Other;
            }
        }
        return detectedOS;
    }

    private static String getForWindows() {
        String result = "";
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("cmd.exe", "/c",
                    "REG.exe QUERY HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography /v MachineGuid");

            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("REG_SZ")) {
                    break;
                }
            }
            result = line.split("REG_SZ")[1].replaceAll("\\s+", "");
            System.out.println("MachineId(Windows): + '" + result + "'");

            int exitCode = process.waitFor();
            System.out.println("MachinId exited with code with error code : " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;

    }

    private static String getForLinux() {
        String result = "";
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c",
                    "( cat /var/lib/dbus/machine-id /etc/machine-id 2> /dev/null || hostname ) | head -n 1 || :");

            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line = reader.readLine();
            result = line.replaceAll("\\s+", "");

            System.out.println("MachineId(Linux): + '" + result + "'");

            int exitCode = process.waitFor();
            System.out.println("MachinId exited with code with error code : " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;

    }

    private static String getForMac() {
        String result = "";
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c", "ioreg -rd1 -c IOPlatformExpertDevice");

            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line = "";
            while ((line = reader.readLine()) != null) {
                if (line.contains("IOPlatformUUID")) {
                    break;
                }
            }
            result = line.split("IOPlatformUUID")[1].replaceAll("[=\\s+\"]", "");


            System.out.println("MachineId(Mac): + '" + result + "'");


            int exitCode = process.waitFor();
            System.out.println("MachinId exited with code with error code : " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;

    }

    private static String getForOthers() {
        String result = "";
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("sh", "-c", "kenv -q smbios.system.uuid || sysctl -n kern.hostuuid");

            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line = reader.readLine();
            result = line.replaceAll("\\s+", "");
            System.out.println("MachineId(Others): + '" + result + "'");

            int exitCode = process.waitFor();
            System.out.println("MachinId exited with code with error code : " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;

    }

    public static String getMachineId() throws Exception {
        OSType osType = getOperatingSystemType();
        String result = "";
        if (osType == OSType.Windows) {
            result = getForWindows();
        } else if (osType == OSType.Linux) {
            result = getForLinux();
        } else if (osType == OSType.MacOS) {
            result = getForMac();
        } else {
            result = getForOthers();
        }
        if(result.isEmpty()) {
            throw new Exception("Could not get machine id");
        }
        return result.toUpperCase();
    }
}
