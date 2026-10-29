package cn.jinjing.app;

import java.nio.file.Files;
import java.nio.file.Path;

final class SyncPaths {
    private SyncPaths() {}

    static Path archiveDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("backend/pom.xml"))) {
            return cwd.resolve("定时数据抓取清单");
        }
        if (Files.isRegularFile(cwd.resolve("pom.xml")) && cwd.getFileName() != null
                && cwd.getFileName().toString().equals("backend")) {
            return cwd.getParent().resolve("定时数据抓取清单");
        }
        return cwd.resolve("定时数据抓取清单");
    }
}
