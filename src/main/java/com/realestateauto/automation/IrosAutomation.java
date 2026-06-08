package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class IrosAutomation {

    private static final String IROS_URL = "https://www.iros.go.kr";
    private static final String COOKIE_FILE = System.getProperty("user.home") + "/iros-cookies.ser";
    private final String savePath;
    private final Consumer<String> logger;

    public IrosAutomation(String savePath, Consumer<String> logger) {
        this.savePath = savePath;
        this.logger = logger;
    }

    public void download(String address) throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,900");

        ChromeDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        try {
            logger.accept("인터넷등기소 접속 중...");
            driver.get(IROS_URL);
            Thread.sleep(5000);

            // 저장된 쿠키로 로그인 시도
            if (loadCookies(driver)) {
                driver.navigate().refresh();
                Thread.sleep(3000);
            }

            // 로그인 상태 확인
            if (!isLoggedIn(driver)) {
                logger.accept("로그인 필요 - 브라우저에서 직접 로그인 후 '로그인 완료' 버튼을 눌러주세요.");
                waitForManualLogin(driver);
                saveCookies(driver);
                logger.accept("로그인 완료. 쿠키 저장됨.");
            } else {
                logger.accept("자동 로그인 성공.");
            }

            logger.accept("주소 검색 중: " + address);
            searchAndDownload(driver, wait, address);

        } finally {
            driver.quit();
        }
    }

    private boolean isLoggedIn(WebDriver driver) {
        String src = driver.getPageSource();
        return src.contains("로그아웃") || src.contains("마이페이지") || src.contains("logout");
    }

    private void waitForManualLogin(ChromeDriver driver) throws InterruptedException {
        // 로그인될 때까지 최대 5분 대기
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            if (isLoggedIn(driver)) {
                return;
            }
        }
        throw new RuntimeException("로그인 대기 시간 초과 (5분)");
    }

    @SuppressWarnings("unchecked")
    private boolean loadCookies(ChromeDriver driver) {
        File file = new File(COOKIE_FILE);
        if (!file.exists()) return false;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            List<Map<String, Object>> cookies = (List<Map<String, Object>>) ois.readObject();
            for (Map<String, Object> c : cookies) {
                try {
                    Cookie cookie = new Cookie.Builder((String) c.get("name"), (String) c.get("value"))
                            .domain((String) c.get("domain"))
                            .path((String) c.get("path"))
                            .build();
                    driver.manage().addCookie(cookie);
                } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveCookies(ChromeDriver driver) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(COOKIE_FILE))) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Cookie c : driver.manage().getCookies()) {
                Map<String, Object> map = new HashMap<>();
                map.put("name", c.getName());
                map.put("value", c.getValue());
                map.put("domain", c.getDomain());
                map.put("path", c.getPath());
                list.add(map);
            }
            oos.writeObject(list);
        } catch (Exception e) {
            logger.accept("쿠키 저장 실패: " + e.getMessage());
        }
    }

    private void searchAndDownload(ChromeDriver driver, WebDriverWait wait, String address) throws Exception {
        // TODO: 로그인 후 등기부등본 발급 흐름 구현
        // 실제 사이트 구조 확인 후 selector 입력 필요
        logger.accept("등기부등본 발급 기능 구현 예정 (로그인 자동화 완료)");
        Thread.sleep(2000);
    }
}
