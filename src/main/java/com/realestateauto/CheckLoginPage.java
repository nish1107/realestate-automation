package com.realestateauto;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.List;

public class CheckLoginPage {
    public static void main(String[] args) throws Exception {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--allow-insecure-localhost");
        ChromeDriver driver = new ChromeDriver(options);

        try {
            // g.iros.go.kr PC 버전 로그인 페이지 직접 접근
            driver.get("https://www.iros.go.kr");
            Thread.sleep(8000);

            System.out.println("=== 메인 로드 완료: " + driver.getCurrentUrl() + " ===");

            // 로그인 버튼 JS 클릭
            List<WebElement> allLinks = driver.findElements(By.tagName("a"));
            WebElement loginLink = null;
            for (WebElement link : allLinks) {
                if (link.getText().trim().contains("로그인")) {
                    loginLink = link;
                    System.out.println("로그인 링크 발견: " + link.getText());
                    break;
                }
            }

            if (loginLink != null) {
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", loginLink);
                System.out.println("JS 클릭 완료, 6초 대기...");
                Thread.sleep(6000);
            }

            System.out.println("=== 클릭 후 URL: " + driver.getCurrentUrl() + " ===");
            System.out.println("=== 클릭 후 창 수: " + driver.getWindowHandles().size() + " ===");

            for (String handle : driver.getWindowHandles()) {
                driver.switchTo().window(handle);
                System.out.println("--- 창: " + driver.getCurrentUrl());
                Object inputInfo = ((org.openqa.selenium.JavascriptExecutor) driver)
                        .executeScript("return Array.from(document.querySelectorAll('input')).map(i => 'id='+i.id+' type='+i.type+' name='+i.name+' placeholder='+i.placeholder).join('||')");
                System.out.println("inputs: " + inputInfo);
            }

        } finally {
            driver.quit();
        }
    }
}
