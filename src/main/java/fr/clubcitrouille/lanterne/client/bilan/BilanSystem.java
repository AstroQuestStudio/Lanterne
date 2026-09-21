package fr.clubcitrouille.lanterne.client.bilan;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/** Ce qui décrit la machine, la JVM et la mémoire — lu par réflexion prudente, jamais bloquant. */
final class BilanSystem {
    private BilanSystem() {}

    static String cpuName() {
        String env = System.getenv("PROCESSOR_IDENTIFIER"); // Windows
        return env != null && !env.isBlank() ? env.strip() : System.getProperty("os.arch", "n/a");
    }

    static long totalRamMb() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize() >> 20;
            }
        } catch (Throwable unavailable) {
            // module jdk.management absent : tant pis
        }
        return -1L;
    }

    /** Arguments de la JVM, sans rien qui ressemble à un secret. */
    static String jvmArgs() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            StringBuilder out = new StringBuilder();
            for (String arg : args) {
                String low = arg.toLowerCase(Locale.ROOT);
                if (low.contains("token") || low.contains("session") || low.contains("password")) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(arg);
            }
            String result = out.toString();
            return result.length() > 900 ? result.substring(0, 900) + "..." : result;
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    private static long lastGcCount;
    private static long lastGcMs;

    /** {compte, durée ms} cumulés des ramasse-miettes depuis le dernier appel de cette méthode. */
    static long[] gcSinceLast() {
        long count = 0;
        long ms = 0;
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c > 0) {
                    count += c;
                }
                if (t > 0) {
                    ms += t;
                }
            }
        } catch (Throwable unavailable) {
            return new long[] {0L, 0L};
        }
        long[] delta = {count - lastGcCount, ms - lastGcMs};
        lastGcCount = count;
        lastGcMs = ms;
        return delta;
    }

    static String gcNames() {
        try {
            StringBuilder out = new StringBuilder();
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (out.length() > 0) {
                    out.append('+');
                }
                out.append(gc.getName().replace(' ', '_'));
            }
            return out.toString();
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    static long heapMaxMb() {
        return Runtime.getRuntime().maxMemory() >> 20;
    }
}
