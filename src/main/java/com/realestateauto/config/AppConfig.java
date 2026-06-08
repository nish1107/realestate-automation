package com.realestateauto.config;

import java.io.*;
import java.util.Properties;

public class AppConfig {

    private static final String CONFIG_FILE = System.getProperty("user.home") + "/realestate-config.properties";
    private final Properties props = new Properties();

    public AppConfig() {
        load();
    }

    private void load() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void save() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "부동산 자동화 설정");
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

    public boolean isConfigured() {
        return !get("iros.id").isEmpty()
                && !get("gov24.id").isEmpty()
                && !get("serve.id").isEmpty();
    }
}
