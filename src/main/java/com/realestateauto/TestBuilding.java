package com.realestateauto;

import com.realestateauto.config.AppConfig;
import com.realestateauto.service.BuildingService;

public class TestBuilding {
    public static void main(String[] args) throws Exception {
        String address = "탑실로 152 203동 102호";
        AppConfig config = new AppConfig();
        String savePath = config.get("savePath").isEmpty()
            ? System.getProperty("user.home") + "\\Desktop\\부동산서류"
            : config.get("savePath");

        System.out.println("=== 건축물대장 테스트: " + address + " ===");
        System.out.println("저장 경로: " + savePath);
        new java.io.File(savePath).mkdirs();

        new BuildingService(config).download(address, savePath, msg -> {
            System.out.println("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
            System.out.flush();
        });
    }
}
