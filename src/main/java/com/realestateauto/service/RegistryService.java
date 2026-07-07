package com.realestateauto.service;

import com.realestateauto.automation.IrosAutomation;
import com.realestateauto.config.AppConfig;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RegistryService {

    private final AppConfig config;

    public RegistryService(AppConfig config) {
        this.config = config;
    }

    public void download(String address, String savePath, String recordType, Consumer<String> logger) throws Exception {
        String irosId = config.get("iros.id");
        String irosPassword = config.get("iros.password");
        String paymentAccount = config.get("iros.paymentAccount");
        String paymentPassword = config.get("iros.paymentPassword");

        List<String> logBuffer = new ArrayList<>();
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");
        Consumer<String> bufferedLogger = msg -> {
            logBuffer.add("[" + LocalTime.now().format(tf) + "] " + msg);
            logger.accept(msg);
        };

        IrosAutomation automation = new IrosAutomation(
            savePath, irosId, irosPassword, paymentAccount, paymentPassword, recordType, bufferedLogger);
        try {
            automation.download(address);
        } catch (Exception e) {
            saveFailedLog(address, savePath, logBuffer, e, logger);
            throw e;
        }
    }

    private void saveFailedLog(String address, String savePath, List<String> logBuffer, Exception e, Consumer<String> logger) {
        try {
            File failDir = new File("실패로그").getAbsoluteFile();
            failDir.mkdirs();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeName = address.replaceAll("[\\\\/:*?\"<>|]", "_");
            File logFile = new File(failDir, "실패_" + timestamp + "_" + safeName + ".txt");

            try (PrintWriter pw = new PrintWriter(logFile, StandardCharsets.UTF_8)) {
                pw.println("주소: " + address);
                pw.println("시각: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                pw.println("오류: " + e);
                pw.println("=".repeat(60));
                for (String line : logBuffer) {
                    pw.println(line);
                }
            }

            logger.accept("[실패로그] 저장: " + logFile.getName());
            new ProcessBuilder("explorer.exe", failDir.getAbsolutePath()).start();
        } catch (Exception ex) {
            logger.accept("[실패로그] 저장 실패: " + ex.getMessage());
        }
    }
}
