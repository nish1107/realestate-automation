package com.realestateauto.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class AppConfig {

    private static final String CONFIG_FILE;
    static {
        if (System.getProperty("jpackage.app-version") != null) {
            // jpackage 패키지 앱: java.home = runtime/ 폴더, 부모 = exe와 같은 폴더
            CONFIG_FILE = new File(System.getProperty("java.home")).getParent()
                          + File.separator + "realestate-config.properties";
        } else {
            // 개발 모드: 기존 user.home 경로 유지
            CONFIG_FILE = System.getProperty("user.home") + "/realestate-config.properties";
        }
    }

    private final Properties props = new Properties();

    public AppConfig() {
        load();
    }

    private void load() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                props.load(isr);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void save() {
        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {
            props.store(osw, "부동산 자동화 설정");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String get(String key) {
        return props.getProperty(key, "");
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }


}
