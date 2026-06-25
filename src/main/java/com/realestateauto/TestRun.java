package com.realestateauto;

import com.realestateauto.config.AppConfig;
import com.realestateauto.service.RegistryService;

public class TestRun {
    public static void main(String[] args) throws Exception {
        String address = "탑실로 152 203동 2202호";
        AppConfig config = new AppConfig();
        String savePath = config.get("savePath").isEmpty()
            ? System.getProperty("user.home") + "\\Desktop\\부동산서류"
            : config.get("savePath");

        System.out.println("=== 테스트 시작: " + address + " ===");
        System.out.println("저장 경로: " + savePath);

        new java.io.File(savePath).mkdirs();
        new RegistryService(config).download(address, savePath, msg -> {
            System.out.println("[" + java.time.LocalTime.now().toString().substring(0,8) + "] " + msg);
            System.out.flush();
        });
    }
}
