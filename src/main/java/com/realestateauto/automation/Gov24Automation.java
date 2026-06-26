package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 세움터(eais.go.kr)에서 건축물대장 자동 다운로드.
 * 로그인 필요 → 자동 로그인 시도 후 실패 시 수동 로그인 대기.
 * 주소 형식: "탑실로 152 203동 102호"
 */
public class Gov24Automation {

    private static final String EAIS_URL = "https://www.eais.go.kr";
    private static final String GOV24_URL = "https://www.gov.kr";

    private final String id;
    private final String password;
    private final String savePath;
    private final String addressType; // "도로명" or "지번"
    private final Consumer<String> logger;

    public Gov24Automation(String id, String password, String savePath, Consumer<String> logger) {
        this(id, password, savePath, "도로명", logger);
    }

    public Gov24Automation(String id, String password, String savePath, String addressType, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.savePath = savePath;
        this.addressType = (addressType != null && !addressType.isEmpty()) ? addressType : "도로명";
        this.logger = logger;
    }

    public void download(String address) throws Exception {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--allow-insecure-localhost");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,900");
        options.addArguments("--window-position=0,0");
        options.addArguments("--start-maximized");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", new File(savePath).getAbsolutePath());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        String profileDir = System.getProperty("user.home") + "/gov24-chrome-profile";
        new File(profileDir).mkdirs();
        // 이전 Chrome 비정상 종료 시 남은 락파일 자동 정리
        for (String lockFile : new String[]{"SingletonLock", "SingletonSocket", "SingletonCookie"}) {
            File f = new File(profileDir + "/" + lockFile);
            if (f.exists()) { f.delete(); logger.accept("[Chrome] 락파일 정리: " + lockFile); }
        }
        options.addArguments("--user-data-dir=" + profileDir);

        ChromeDriver driver = new ChromeDriver(options);
        try {
            AddressParts parts = parseAddress(address);
            logger.accept("건물주소: " + parts.buildingAddress
                + (parts.dong.isEmpty() ? "" : " / " + parts.dong + "동")
                + (parts.ho.isEmpty() ? "" : " " + parts.ho + "호"));

            tryGov24(driver, parts);
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    // ─── 세움터(eais.go.kr) ─────────────────────────────────────────────

    private boolean tryEais(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        try {
            logger.accept("세움터(건축행정시스템) 접속 중...");
            driver.get(EAIS_URL);
            waitForText(driver, 10000, "세움터", "건축물", "로그인", "민원");

            // 팝업 2번 닫기 (delayed popup 대비)
            dismissPopups(driver);
            Thread.sleep(800);
            dismissPopups(driver);
            Thread.sleep(500);
            saveScreenshot(driver, "01_eais_home");

            String pageText = getPageText(driver);
            // 서비스 완전 중단만 체크 (future maintenance 공지는 무시)
            if (pageText.contains("서비스를 이용") && pageText.contains("일시 중지")) {
                logger.accept("세움터 서비스 중단 - 정부24로 전환");
                return false;
            }

            // 건축물대장 메뉴 클릭 (로그인 여부 무관)
            logger.accept("건축물대장 메뉴 탐색...");
            boolean menuClicked = clickLinkByText(driver, "건축물대장");
            if (!menuClicked) {
                // GNB 메뉴에서 찾기
                menuClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "var menus = document.querySelectorAll('nav a, header a, .gnb a, .lnb a, .menu a, ul li a');" +
                    "for (var i = 0; i < menus.length; i++) {" +
                    "  var a = menus[i];" +
                    "  var rect = a.getBoundingClientRect();" +
                    "  if (rect.width > 0 && (a.innerText||'').includes('건축물')) {" +
                    "    a.click(); return true;" +
                    "  }" +
                    "}" +
                    "return false;");
            }
            logger.accept("건축물대장 메뉴 클릭: " + menuClicked);
            Thread.sleep(2000);
            dismissPopups(driver);
            Thread.sleep(500);
            saveScreenshot(driver, "02_eais_building_page");
            pageText = getPageText(driver);

            // 로그인 선택 페이지 감지 (건축물대장발급 - 회원/비회원 선택)
            if (pageText.contains("회원 발급") || pageText.contains("비회원 발급") || pageText.contains("로그인 유형")) {
                logger.accept("세움터 건축물대장 발급 페이지 - 로그인 처리 중...");
                boolean loggedIn = doEaisLogin(driver);
                if (!loggedIn) {
                    logger.accept("세움터 로그인 실패 - 정부24로 전환");
                    return false;
                }
                logger.accept("세움터 로그인 완료");
                // 로그인 후 건축물대장 발급 페이지 재이동
                Thread.sleep(1500);
                dismissPopups(driver);
                driver.get(EAIS_URL + "/buld/R00BRLT001L0.do");
                Thread.sleep(2000);
                dismissPopups(driver);
                saveScreenshot(driver, "02e_building_after_login");
                pageText = getPageText(driver);
            }

            // 건축물대장 주소 검색 페이지 확인
            if (pageText.contains("소재지") || pageText.contains("지번") || pageText.contains("건물 검색")
                    || pageText.contains("건축물대장") || pageText.contains("대장 발급")) {
                logger.accept("건축물대장 주소 검색 중...");
                return searchEais(driver, parts);
            }

            // 메뉴 재클릭 시도
            logger.accept("건축물대장 메뉴 재탐색...");
            clickLinkByText(driver, "건축물대장");
            Thread.sleep(2000);
            dismissPopups(driver);
            saveScreenshot(driver, "02f_menu_retry");
            pageText = getPageText(driver);
            if (pageText.contains("소재지") || pageText.contains("지번") || pageText.contains("건축물대장")) {
                return searchEais(driver, parts);
            }

            logger.accept("세움터 건축물대장 검색 페이지 접근 실패");
            return false;

        } catch (Exception e) {
            logger.accept("세움터 오류: " + e.getMessage());
            return false;
        }
    }

    /**
     * 세움터 로그인: 건축물대장발급 선택 페이지에서 호출됨.
     * "로그인 하기" → 정부통합로그인 페이지 → "아이디 로그인" → ID/PW 입력
     */
    private boolean doEaisLogin(ChromeDriver driver) throws InterruptedException {
        boolean loginBtnClicked = clickButtonByText(driver, "로그인 하기");
        logger.accept("로그인 하기 클릭: " + loginBtnClicked);
        Thread.sleep(2000);

        // 브라우저 alert 닫기 (보안모듈 알림)
        dismissBrowserAlert(driver);

        // 보안프로그램 오버레이 소멸 대기 (최대 12초 폴링)
        logger.accept("보안프로그램 오버레이 소멸 대기...");
        for (int i = 0; i < 24; i++) {
            Thread.sleep(500);
            dismissBrowserAlert(driver);
            String bodyText = getPageText(driver);
            if (bodyText.contains("아이디 로그인") && !bodyText.contains("보안프로그램이 로딩중")) {
                logger.accept("오버레이 소멸 확인 (" + ((i + 1) * 500) + "ms)");
                break;
            }
        }
        Thread.sleep(300);
        saveScreenshot(driver, "02b_eais_login_page");

        // "아이디 로그인" 클릭 (다중 전략)
        String eaisMainHandle = driver.getWindowHandle();
        boolean idLoginClicked = clickIdLoginCard(driver, "세움터");
        logger.accept("아이디 로그인 클릭: " + idLoginClicked);
        Thread.sleep(2500);
        dismissBrowserAlert(driver);

        // 새 창 확인 (아이디 로그인이 팝업/새탭으로 열리는 경우)
        Set<String> eaisWins = driver.getWindowHandles();
        logger.accept("창 개수: " + eaisWins.size() + ", 현재URL: " + driver.getCurrentUrl());
        if (eaisWins.size() > 1) {
            for (String h : eaisWins) {
                if (!h.equals(eaisMainHandle)) {
                    logger.accept("새 창 감지 - 아이디 로그인 폼 창으로 전환");
                    driver.switchTo().window(h);
                    Thread.sleep(1500);
                    dismissBrowserAlert(driver);
                    logger.accept("새 창 URL: " + driver.getCurrentUrl());
                    break;
                }
            }
        } else {
            Thread.sleep(500);
        }
        saveScreenshot(driver, "02c_eais_id_login_form");

        // ID/PW 입력 시도
        if (!id.isEmpty() && !password.isEmpty()) {
            try {
                WebElement idInput = findVisibleInput(driver, "아이디", "id", "userid", "loginId", "memberId");
                if (idInput != null) {
                    idInput.clear();
                    idInput.sendKeys(id);
                    Thread.sleep(200);
                }
                List<WebElement> pwInputs = driver.findElements(By.cssSelector("input[type=password]"));
                for (WebElement el : pwInputs) {
                    if (el.isDisplayed()) {
                        el.clear();
                        el.sendKeys(password);
                        Thread.sleep(200);
                        el.sendKeys(Keys.ENTER);
                        break;
                    }
                }
                Thread.sleep(2000);
                dismissBrowserAlert(driver);
                // 로그인 실패 모달("아이디 또는 비밀번호를 확인바랍니다") 닫기
                dismissPopups(driver);
                Thread.sleep(500);
                saveScreenshot(driver, "02d_after_auto_login");
                String afterLoginText = getPageText(driver);
                if (afterLoginText.contains("아이디 또는 비밀번호") || afterLoginText.contains("비밀번호를 확인")) {
                    logger.accept("세움터 로그인 실패: 아이디/비밀번호 오류 - 정부24로 전환");
                    return false;
                }
                // 모든 창 확인 (로그인 성공 후 원래 창으로 돌아왔을 수 있음)
                for (String h : driver.getWindowHandles()) {
                    driver.switchTo().window(h);
                    if (isLoggedIn(driver)) return true;
                    String text = getPageText(driver);
                    if (text.contains("소재지") || text.contains("지번") || text.contains("대장 발급")
                            || (text.contains("건축물") && text.contains("주소"))) {
                        return true;
                    }
                }
            } catch (Exception e) {
                logger.accept("세움터 자동 로그인 실패: " + e.getMessage());
            }
        }

        // 수동 로그인 대기 (1분 - EAIS 계정이 있는 경우만 유용)
        logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
        logger.accept("세움터 수동 로그인 대기 (최대 1분)");
        logger.accept("세움터 계정이 있으면 로그인해주세요. 없으면 잠시 후 정부24로 자동 전환됩니다.");
        logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            dismissBrowserAlert(driver);
            dismissPopups(driver);
            if (isLoggedIn(driver)) return true;
            String text = getPageText(driver);
            if (text.contains("소재지") || text.contains("지번") || text.contains("건물 검색")
                    || (text.contains("건축물") && text.contains("주소"))) {
                return true;
            }
        }
        return false;
    }

    /** 브라우저 native alert (공인인증서 모듈 알림 등) 닫기 */
    private void dismissBrowserAlert(ChromeDriver driver) {
        try {
            Alert alert = driver.switchTo().alert();
            String alertText = alert.getText();
            logger.accept("브라우저 알림 닫기: " + alertText.substring(0, Math.min(60, alertText.length())));
            alert.accept();
            Thread.sleep(300);
        } catch (Exception ignored) {}
    }

    /**
     * "아이디 로그인" 카드 클릭 (세움터/정부24 공통).
     * innerText 대신 textContent 사용 (overlay의 inert/visibility:hidden 우회).
     * 실패 시 XPath, scrollIntoView, dispatchEvent 순으로 폴백.
     */
    private boolean clickIdLoginCard(ChromeDriver driver, String site) {
        // 디버그: "아이디"를 포함하는 요소 정보 출력
        try {
            String debug = (String) ((JavascriptExecutor) driver).executeScript(
                "var result = [];" +
                "var all = document.querySelectorAll('*');" +
                "for (var i = 0; i < all.length; i++) {" +
                "  var tc = (all[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  if (tc.includes('아이디 로그인') && tc.length < 200) {" +
                "    var rect = all[i].getBoundingClientRect();" +
                "    result.push(all[i].tagName + '[w=' + Math.round(rect.width) + ',h=' + Math.round(rect.height) + '] \"' + tc.substring(0, 30) + '\"');" +
                "    if (result.length >= 5) break;" +
                "  }" +
                "}" +
                "return result.length > 0 ? result.join(' | ') : 'NOT FOUND';");
            logger.accept("[" + site + "] 아이디로그인 요소: " + debug);
        } catch (Exception ignored) {}

        // 방법 1: A/BUTTON 태그에서 textContent 검색 (소셜 로그인 URL 제외)
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var links = document.querySelectorAll('a, button');" +
                "for (var i = 0; i < links.length; i++) {" +
                "  var el = links[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  var href = el.getAttribute('href') || '';" +
                // 소셜 미디어 공유/로그인 URL 제외
                "  if (/x\\.com|twitter|kakao|naver\\.com\\/oauth|facebook|google/.test(href)) continue;" +
                "  if (tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    el.scrollIntoView({block:'center'});" +
                "    var target = el.getAttribute('target') || 'same';" +
                "    el.click();" +
                "    return 'clicked|href=' + href + '|target=' + target;" +
                "  }" +
                "}" +
                "return null;");
            if (result != null) {
                logger.accept("[" + site + "] 방법1 성공: " + result);
                return true;
            }
        } catch (Exception ignored) {}

        // 방법 2: 모든 요소에서 textContent 검색, A/BUTTON 우선
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var all = document.querySelectorAll('*');" +
                "var fallback = null;" +
                "for (var i = 0; i < all.length; i++) {" +
                "  var el = all[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  if (rect.width > 0 && rect.height > 0 && tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    var tag = el.tagName;" +
                "    if (tag === 'A' || tag === 'BUTTON') {" +
                "      el.scrollIntoView({block:'center'});" +
                "      el.click();" +
                "      return 'A/BUTTON|tag=' + tag;" +
                "    }" +
                "    if (!fallback) fallback = el;" +
                "  }" +
                "}" +
                "if (fallback) { fallback.scrollIntoView({block:'center'}); fallback.click(); return 'fallback|' + fallback.tagName; }" +
                "return null;");
            if (result != null) {
                logger.accept("[" + site + "] 방법2 성공: " + result);
                return true;
            }
        } catch (Exception ignored) {}

        // 방법 3: XPath - 직접 텍스트가 "아이디 로그인"인 요소
        try {
            List<WebElement> els = driver.findElements(By.xpath("//*[normalize-space(text())='아이디 로그인']"));
            for (WebElement el : els) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(200);
                    el.click();
                    logger.accept("[" + site + "] 방법3(XPath text) 성공");
                    return true;
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}

        // 방법 4: XPath - "아이디 로그인"을 포함하는 링크/버튼
        try {
            List<WebElement> els = driver.findElements(By.xpath("//a[contains(.,'아이디 로그인')] | //button[contains(.,'아이디 로그인')]"));
            for (WebElement el : els) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(200);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click()", el);
                    logger.accept("[" + site + "] 방법4(XPath a/button) 성공");
                    return true;
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}

        // 방법 5: 페이지 스크롤 후 재시도
        try {
            Thread.sleep(300);
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight)");
            Thread.sleep(500);
            Boolean clicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var all = document.querySelectorAll('*');" +
                "for (var i = all.length - 1; i >= 0; i--) {" +
                "  var el = all[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  if (rect.width > 0 && rect.height > 0 && tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    el.click(); return true;" +
                "  }" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(clicked)) { logger.accept("[" + site + "] 방법5(scroll+retry) 성공"); return true; }
        } catch (Exception ignored) {}

        logger.accept("[" + site + "] 모든 클릭 방법 실패");
        return false;
    }

    private boolean searchEais(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        String searchAddr = parts.buildingAddress;

        // 주소 입력 필드 찾기 (sendKeys 방식)
        WebElement addrInput = findVisibleInput(driver, "주소", "건물", "addr", "address", "소재지", "지번");
        if (addrInput != null) {
            addrInput.clear();
            addrInput.sendKeys(searchAddr);
            Thread.sleep(300);
            addrInput.sendKeys(Keys.ENTER);
            logger.accept("주소 입력: " + searchAddr);
        } else {
            // 주소 검색 버튼 클릭 시도
            boolean popupOpened = clickButtonByText(driver, "주소검색", "주소 검색", "주소찾기", "검색");
            if (popupOpened) {
                Thread.sleep(1500);
                handleAddressPopup(driver, searchAddr);
            } else {
                logger.accept("주소 입력 필드 없음 - 페이지 소스 확인");
                saveScreenshot(driver, "03_no_input");
                return false;
            }
        }

        Thread.sleep(2000);
        waitForText(driver, 8000, "건축물대장", "호", "동", "선택", "결과", "목록");
        saveScreenshot(driver, "03_search_result");
        String resultText = getPageText(driver);
        logger.accept("검색결과: " + resultText.substring(0, Math.min(200, resultText.length())).replaceAll("\\s+", " "));

        // 결과에서 동/호 매칭
        String resultClick = (String) ((JavascriptExecutor) driver).executeScript(
            "var dong = arguments[0], ho = arguments[1];" +
            "var rows = document.querySelectorAll('tr, li, .result-item, .list-item');" +
            "var firstBtn = null;" +
            "for (var i = 0; i < rows.length; i++) {" +
            "  var t = (rows[i].innerText || '').trim();" +
            "  if (!t) continue;" +
            "  var btns = rows[i].querySelectorAll('a, button');" +
            "  if (btns.length === 0) continue;" +
            "  var rect = btns[0].getBoundingClientRect();" +
            "  if (rect.width === 0) continue;" +
            "  if (firstBtn === null) firstBtn = btns[0];" +
            "  if (dong && ho && t.includes(dong + '동') && t.includes(ho + '호')) {" +
            "    btns[0].click(); return '매칭:' + t.substring(0, 40);" +
            "  }" +
            "}" +
            "if (firstBtn) { firstBtn.click(); return '첫번째결과'; }" +
            "return 'notfound';",
            parts.dong, parts.ho);
        logger.accept("결과 선택: " + resultClick);

        if ("notfound".equals(resultClick)) {
            saveScreenshot(driver, "03_notfound");
            logger.accept("검색 결과 없음");
            return false;
        }

        Thread.sleep(2000);
        saveScreenshot(driver, "04_after_select");
        return downloadOrPrint(driver);
    }

    // ─── 정부24(gov.kr) ──────────────────────────────────────────────────

    private void tryGov24(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        logger.accept("정부24 접속 중...");
        driver.get(GOV24_URL);
        waitForText(driver, 8000, "로그인", "로그아웃", "민원서비스");
        dismissPopups(driver);
        Thread.sleep(500);

        String pageText = getPageText(driver);
        // 점검 감지: 명확한 점검 문구 + 로그인/로그아웃 버튼도 없어야 진짜 점검
        boolean isMaintenance = (pageText.contains("서비스 점검") || pageText.contains("현재 점검") || pageText.contains("시스템 점검"))
                && !pageText.contains("로그인") && !pageText.contains("로그아웃");
        if (isMaintenance) {
            logger.accept("정부24 서비스 점검 중 - " + pageText.substring(0, Math.min(80, pageText.length())));
            throw new RuntimeException("정부24 서비스 점검 중입니다.\n잠시 후 다시 시도해주세요.");
        }

        if (!isLoggedIn(driver)) {
            if (!id.isEmpty() && !password.isEmpty()) {
                logger.accept("정부24 로그인 시도...");
                doGov24Login(driver);
            }
            if (!isLoggedIn(driver)) {
                logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.accept("정부24 자동 로그인 실패 - 수동 로그인 필요");
                logger.accept("열린 창에서 직접 로그인해주세요. (최대 5분 대기)");
                logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                if (!waitForManualLogin(driver)) {
                    logger.accept("로그인 없이 종료.");
                    return;
                }
            }
        }
        logger.accept("로그인 확인됨.");

        logger.accept("건축물대장 서비스 이동 중...");
        navigateToBuildingService(driver);
        logger.accept("주소 검색: " + parts.buildingAddress + " [" + addressType + "]");
        boolean success = fillBuildingForm(driver, parts, "집합");
        if (!success) {
            logger.accept("[재시도] 집합 대장구분 검색 실패 - 일반으로 재시도...");
            navigateToBuildingService(driver);
            fillBuildingForm(driver, parts, "일반");
        }
    }

    /** 정부24 로그인: 로그인 방식 선택 → "아이디 로그인" 클릭 → ID/PW sendKeys */
    private void doGov24Login(ChromeDriver driver) throws InterruptedException {
        driver.get(GOV24_URL);
        Thread.sleep(1500);
        dismissPopups(driver);
        Thread.sleep(300);

        // 메인 헤더의 로그인 링크 클릭 → 로그인 방식 선택 페이지
        clickLinkByText(driver, "로그인");
        Thread.sleep(2000);
        dismissBrowserAlert(driver);

        // 페이지 로드 완료 대기
        for (int i = 0; i < 12; i++) {
            Thread.sleep(500);
            dismissBrowserAlert(driver);
            String bodyText = getPageText(driver);
            if (bodyText.contains("아이디 로그인")) break;
        }
        Thread.sleep(300);
        saveScreenshot(driver, "gov24_login_page");

        // "아이디 로그인" 클릭 (다중 전략)
        String gov24MainHandle = driver.getWindowHandle();
        boolean idLoginClicked = clickIdLoginCard(driver, "정부24");
        logger.accept("정부24 아이디 로그인 클릭: " + idLoginClicked);
        Thread.sleep(2500);
        dismissBrowserAlert(driver);

        // 새 창 확인 (아이디 로그인이 팝업/새탭으로 열리는 경우)
        Set<String> gov24Wins = driver.getWindowHandles();
        logger.accept("창 개수: " + gov24Wins.size() + ", 현재URL: " + driver.getCurrentUrl());
        if (gov24Wins.size() > 1) {
            for (String h : gov24Wins) {
                if (!h.equals(gov24MainHandle)) {
                    logger.accept("새 창 감지 - 아이디 로그인 폼 창으로 전환");
                    driver.switchTo().window(h);
                    Thread.sleep(1500);
                    dismissBrowserAlert(driver);
                    logger.accept("새 창 URL: " + driver.getCurrentUrl());
                    break;
                }
            }
        } else {
            Thread.sleep(500);
        }
        saveScreenshot(driver, "gov24_id_login_form");

        String pageText = getPageText(driver);
        if (!pageText.contains("아이디") && !pageText.contains("비밀번호")) {
            logger.accept("정부24 아이디 로그인 폼 로드 실패 - 페이지: " + pageText.substring(0, Math.min(100, pageText.length())));
            return;
        }

        try {
            // Step 1: ID 입력 → "다음" 클릭
            WebElement idInput = findVisibleInput(driver, "아이디", "id", "userId", "loginId");
            if (idInput != null) {
                idInput.clear();
                idInput.sendKeys(id);
                Thread.sleep(300);
                // "로그인 유지" 체크박스 클릭 (세션 30일 유지)
                try {
                    Boolean keepLogin = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "var cbs=document.querySelectorAll('input[type=checkbox]');" +
                        "for(var i=0;i<cbs.length;i++){" +
                        "  var lbl='';" +
                        "  if(cbs[i].labels&&cbs[i].labels[0]) lbl=cbs[i].labels[0].textContent||'';" +
                        "  else { var s=cbs[i].nextSibling; while(s){if(s.textContent) {lbl=s.textContent; break;} s=s.nextSibling;}}" +
                        "  lbl=lbl.replace(/\\s+/g,' ').trim();" +
                        "  if((lbl.includes('로그인 유지')||lbl.includes('자동 로그인')||lbl.includes('로그인유지'))&&!cbs[i].checked){" +
                        "    cbs[i].click(); return true;" +
                        "  }" +
                        "} return false;");
                    if (Boolean.TRUE.equals(keepLogin)) logger.accept("로그인 유지 체크박스 클릭 완료");
                } catch (Exception ignored) {}
                clickButtonByText(driver, "다음");
                Thread.sleep(2500);
                dismissBrowserAlert(driver);
                saveScreenshot(driver, "gov24_after_id_step");
                logger.accept("ID입력 후 URL: " + driver.getCurrentUrl());
            }
            // Step 2: 비밀번호 입력
            Thread.sleep(2000);
            // 이미 로그인된 경우 건너뜀
            if (isLoggedIn(driver)) {
                logger.accept("로그인 완료 확인 (비밀번호/CAPTCHA 불필요)");
            } else {
            String pwPageText = getPageText(driver);
            // "자동입력" 단독 제거 - false-positive 방지. 실제 CAPTCHA 텍스트만 체크
            boolean hasCaptcha = pwPageText.contains("아래의 숫자") || pwPageText.contains("보안문자")
                    || pwPageText.contains("자동입력방지코드") || pwPageText.contains("자동입력 방지코드")
                    || (pwPageText.contains("captcha") && !pwPageText.contains("로그아웃"));
            // PW 페이지의 "로그인 유지" 체크박스 (아직 안 눌렸을 수 있음)
            try {
                ((JavascriptExecutor) driver).executeScript(
                    "var cbs=document.querySelectorAll('input[type=checkbox]');" +
                    "for(var i=0;i<cbs.length;i++){" +
                    "  var lbl='';" +
                    "  if(cbs[i].labels&&cbs[i].labels[0]) lbl=cbs[i].labels[0].textContent||'';" +
                    "  else { var s=cbs[i].nextSibling; while(s){if(s.textContent) {lbl=s.textContent; break;} s=s.nextSibling;}}" +
                    "  lbl=lbl.replace(/\\s+/g,' ').trim();" +
                    "  if((lbl.includes('로그인 유지')||lbl.includes('자동 로그인')||lbl.includes('로그인유지'))&&!cbs[i].checked){" +
                    "    cbs[i].click();" +
                    "  }" +
                    "}");
            } catch (Exception ignored) {}
            List<WebElement> pwInputs = driver.findElements(By.cssSelector("input[type=password]"));
            for (WebElement el : pwInputs) {
                if (el.isDisplayed()) {
                    el.clear();
                    el.sendKeys(password);
                    Thread.sleep(300);
                    break;
                }
            }
            if (hasCaptcha) {
                saveScreenshot(driver, "gov24_captcha_waiting");
                beepAlert();
                boolean captchaOk = waitForCaptchaAndEnter(driver, "초기로그인");
                if (captchaOk) {
                    clickLoginSubmitButton(driver, pwInputs);
                    Thread.sleep(1000);
                }
            } else {
                clickLoginSubmitButton(driver, pwInputs);
            }
            waitForText(driver, 300000, "로그아웃");
            } // end !isLoggedIn block
            // 초기 로그인 후 비밀번호 변경 페이지 건너뛰기
            try {
                Thread.sleep(500);
                String initPostUrl = driver.getCurrentUrl();
                String initPostText = getPageText(driver);
                if (initPostUrl.contains("modifyPswd") || initPostUrl.contains("changePswd") || initPostUrl.contains("chgPswd")
                        || initPostText.contains("비밀번호 재설정") || initPostText.contains("비밀번호를 변경")) {
                    logger.accept("[초기로그인] 비밀번호 변경 페이지 감지 - 건너뛰기...");
                    Boolean skipBtn = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "var els=document.querySelectorAll('button,a,input[type=button]');" +
                        "for(var i=0;i<els.length;i++){" +
                        "  var t=(els[i].textContent||els[i].value||'').replace(/\\s+/g,' ').trim();" +
                        "  var r=els[i].getBoundingClientRect();" +
                        "  if(r.width>0&&r.height>0&&(t.includes('다음에')||t.includes('나중에')||t.includes('건너뛰기')||t.includes('skip')||t.includes('취소')||t.includes('닫기'))){" +
                        "    els[i].click(); return true;" +
                        "  }" +
                        "}" +
                        "return false;");
                    logger.accept("[초기로그인] 비밀번호 변경 건너뛰기: " + skipBtn);
                    Thread.sleep(2000);
                    dismissBrowserAlert(driver);
                }
            } catch (Exception e) {
                logger.accept("[초기로그인] 비밀번호 변경 처리 오류: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.accept("정부24 자동 로그인 오류: " + e.getMessage());
        }
    }

    private void navigateToBuildingService(ChromeDriver driver) throws InterruptedException {
        // 전략 1: 메인 페이지 "건축물대장" 바로가기 링크 클릭
        driver.get(GOV24_URL);
        Thread.sleep(2000);
        dismissPopups(driver);

        Boolean clicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var links = document.querySelectorAll('a');" +
            "for (var i = 0; i < links.length; i++) {" +
            "  var t = (links[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var rect = links[i].getBoundingClientRect();" +
            "  if (rect.width > 0 && rect.height > 0 && t.includes('건축물대장')) {" +
            "    links[i].click(); return true;" +
            "  }" +
            "}" +
            "return false;");
        logger.accept("메인페이지 건축물대장 링크 클릭: " + clicked);

        if (Boolean.TRUE.equals(clicked)) {
            Thread.sleep(2500);
            dismissPopups(driver);
            saveScreenshot(driver, "gov24_building_service");
            logger.accept("서비스 이동 후 URL: " + driver.getCurrentUrl());
            String pageText = getPageText(driver);
            logger.accept("서비스페이지: " + pageText.substring(0, Math.min(120, pageText.length())).replaceAll("\\s+", " "));
            if (isOnBuildingServicePage(driver)) {
                logger.accept("건축물대장 서비스 페이지 로드 완료");
                return;
            }
        }

        // 전략 2: 검색창으로 "건축물대장" 검색
        driver.get(GOV24_URL);
        Thread.sleep(1500);
        dismissPopups(driver);

        // 검색창 찾기 (돋보기 버튼 토글이 필요한 경우 대비)
        WebElement searchInput = findVisibleInput(driver, "통합검색", "검색어", "search", "query", "검색");
        if (searchInput == null) {
            ((JavascriptExecutor) driver).executeScript(
                "var btns = document.querySelectorAll('button, a');" +
                "for (var i = 0; i < btns.length; i++) {" +
                "  var t = (btns[i].textContent || btns[i].getAttribute('aria-label') || '').trim();" +
                "  var rect = btns[i].getBoundingClientRect();" +
                "  if (rect.width > 0 && (t === '검색' || t.includes('통합검색') || t.includes('search'))) {" +
                "    btns[i].click(); return;" +
                "  }" +
                "}");
            Thread.sleep(800);
            searchInput = findFirstVisibleInput(driver);
        }

        if (searchInput != null) {
            searchInput.clear();
            searchInput.sendKeys("건축물대장");
            searchInput.sendKeys(Keys.ENTER);
            Thread.sleep(3000);
            dismissPopups(driver);
            saveScreenshot(driver, "gov24_search_result");

            // 검색 결과에서 정확히 "건축물대장" 텍스트인 링크 우선 클릭
            Boolean resultClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var links = document.querySelectorAll('a');" +
                "var fallback = null;" +
                "for (var i = 0; i < links.length; i++) {" +
                "  var t = (links[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = links[i].getBoundingClientRect();" +
                "  if (rect.width === 0 || rect.height === 0) continue;" +
                "  if (t === '건축물대장') { links[i].click(); return true; }" +
                "  if (t.includes('건축물대장') && t.length < 30 && fallback === null) fallback = links[i];" +
                "}" +
                "if (fallback) { fallback.click(); return true; }" +
                "return false;");

            if (Boolean.TRUE.equals(resultClicked)) {
                Thread.sleep(2500);
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_building_service");
                logger.accept("서비스 이동(검색) 후 URL: " + driver.getCurrentUrl());
                String pageText = getPageText(driver);
                logger.accept("서비스페이지(검색): " + pageText.substring(0, Math.min(120, pageText.length())).replaceAll("\\s+", " "));
                if (isOnBuildingServicePage(driver)) {
                    logger.accept("건축물대장 서비스 페이지 로드 완료 (검색)");
                    return;
                }
            }
        }

        saveScreenshot(driver, "gov24_building_service_notfound");
        throw new RuntimeException("건축물대장 서비스 페이지를 찾지 못했습니다. dbg_gov24_building_service_notfound.png 확인");
    }

    private boolean formHasAddressField(String pageText) {
        return pageText.contains("주소검색") || pageText.contains("주소 검색") ||
               pageText.contains("소재지") || pageText.contains("지번") ||
               pageText.contains("도로명") || pageText.contains("대장종류");
    }

    /** 실제 서비스 신청 가능 페이지인지 확인 */
    private boolean isOnBuildingServicePage(ChromeDriver driver) {
        String url = driver.getCurrentUrl();
        // CappBizCD URL이면 서비스 페이지로 간주 (info 또는 apply 페이지)
        if (url.contains("CappBizCD=") || url.contains("InfoCappView") || url.contains("InfoCappApply")) {
            logger.accept("서비스 페이지 URL 확인: " + url);
            return true;
        }
        // 페이지 텍스트 기반 확인 (주소검색/소재지번은 메뉴에 없음)
        String pageText = getPageText(driver);
        return pageText.contains("주소검색") || pageText.contains("소재지번") ||
               pageText.contains("대장종류") || pageText.contains("발급 신청");
    }

    private boolean fillBuildingForm(ChromeDriver driver, AddressParts parts, String daejangGubun) throws InterruptedException {
        // 서비스 정보 페이지 확인 및 "신청하기" 클릭
        saveScreenshot(driver, "gov24_before_apply");
        logger.accept("서비스 페이지 URL: " + driver.getCurrentUrl());
        String beforeText = getPageText(driver);
        logger.accept("신청 전 페이지: " + beforeText.substring(0, Math.min(150, beforeText.length())).replaceAll("\\s+", " "));

        // JS로 "신청하기" 버튼 찾아 클릭 (a/button 우선, 그 다음 모든 태그)
        String jsResult = (String) ((JavascriptExecutor) driver).executeScript(
            "var keywords = ['신청하기', '발급하기', '민원신청', '열람하기'];" +
            // 1단계: a/button 에서 정확히 매칭
            "var interactive = document.querySelectorAll('a, button');" +
            "for (var j = 0; j < interactive.length; j++) {" +
            "  var t0 = (interactive[j].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var r0 = interactive[j].getBoundingClientRect();" +
            "  if (r0.width === 0 || r0.height === 0) continue;" +
            "  for (var k0 = 0; k0 < keywords.length; k0++) {" +
            "    if (t0 === keywords[k0]) { interactive[j].scrollIntoView({block:'center'}); interactive[j].click(); return '클릭(링크):' + t0 + '|' + interactive[j].tagName; }" +
            "  }" +
            "}" +
            // 2단계: 모든 태그에서 매칭 (fallback)
            "var all = document.querySelectorAll('*');" +
            "for (var i = 0; i < all.length; i++) {" +
            "  var el = all[i];" +
            "  var t = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var rect = el.getBoundingClientRect();" +
            "  if (rect.width === 0 || rect.height === 0) continue;" +
            "  for (var k = 0; k < keywords.length; k++) {" +
            "    if (t === keywords[k]) { el.scrollIntoView({block:'center'}); el.click(); return '클릭(DIV):' + t + '|' + el.tagName; }" +
            "  }" +
            "}" +
            // 디버그: 신청 포함 요소 목록
            "var nearby = [];" +
            "for (var i2 = 0; i2 < all.length; i2++) {" +
            "  var t2 = (all[i2].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var r2 = all[i2].getBoundingClientRect();" +
            "  if (r2.width > 0 && r2.height > 0 && (t2.includes('신청') || t2.includes('발급')) && t2.length < 15) {" +
            "    nearby.push(all[i2].tagName + ':\"' + t2 + '\"');" +
            "    if (nearby.length >= 8) break;" +
            "  }" +
            "}" +
            "return '실패|요소:' + nearby.join(',');");
        logger.accept("신청하기 JS 클릭: " + jsResult);

        if (jsResult != null && jsResult.startsWith("클릭")) {
            Thread.sleep(2500);
            dismissBrowserAlert(driver);
            dismissPopups(driver);
            // 회원/비회원 모달 감지 → "회원 신청하기" 클릭
            String modalCheck = getPageText(driver);
            if (modalCheck.contains("회원 신청하기") || modalCheck.contains("비회원 신청하기") || modalCheck.contains("신청가능 서비스")) {
                logger.accept("회원/비회원 모달 감지 - 회원 신청하기 클릭...");
                Set<String> handlesBeforeModal = driver.getWindowHandles();
                Boolean modalClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "var els = document.querySelectorAll('button, a');" +
                    "for (var i = 0; i < els.length; i++) {" +
                    "  var t = (els[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                    "  var rect = els[i].getBoundingClientRect();" +
                    "  if (rect.width > 0 && rect.height > 0 && (t === '회원 신청하기' || t === '회원신청하기')) {" +
                    "    els[i].scrollIntoView({block:'center'}); els[i].click(); return true;" +
                    "  }" +
                    "}" +
                    "return false;");
                logger.accept("회원 신청하기 클릭: " + modalClicked);
                if (Boolean.TRUE.equals(modalClicked)) {
                    Thread.sleep(3000);
                    dismissBrowserAlert(driver);
                    // 새 창이 열렸으면 전환 (회원 신청하기가 새 탭/팝업 오픈)
                    try {
                        Set<String> newHandles = driver.getWindowHandles();
                        String currentHandle = null;
                        try { currentHandle = driver.getWindowHandle(); } catch (Exception ignored) {}
                        for (String h : newHandles) {
                            if (!handlesBeforeModal.contains(h)) {
                                driver.switchTo().window(h);
                                logger.accept("새 창 전환: " + driver.getCurrentUrl());
                                break;
                            }
                        }
                        if (currentHandle == null && !newHandles.isEmpty()) {
                            driver.switchTo().window(newHandles.iterator().next());
                        }
                    } catch (Exception e) {
                        logger.accept("창 전환 오류: " + e.getMessage());
                    }
                    dismissPopups(driver);
                    saveScreenshot(driver, "gov24_after_member_select");
                    logger.accept("회원 선택 후 URL: " + driver.getCurrentUrl());
                }
            }
        }
        saveScreenshot(driver, "gov24_apply_form");
        logger.accept("신청 후 URL: " + driver.getCurrentUrl());
        String applyPageText = getPageText(driver);
        logger.accept("신청 폼 페이지: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));

        // 폼이 '신청할 서비스를 선택하세요' 화면이면 스크롤 다운하여 실제 폼 필드 확인
        if (applyPageText.contains("신청할 서비스") || applyPageText.contains("선택하세요") || applyPageText.contains("신청 내용")) {
            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 1200);");
            Thread.sleep(700);
            saveScreenshot(driver, "gov24_form_scrolled");
            logger.accept("폼 스크롤 후 URL: " + driver.getCurrentUrl());
            applyPageText = getPageText(driver);
        }

        // 신청 폼이 로드되었는지 확인
        boolean hasAddressForm = formHasAddressField(applyPageText);

        // 회원 신청하기 후 재인증 리다이렉트 처리 (plus.gov.kr/login)
        if (!hasAddressForm) {
            String curUrl2 = driver.getCurrentUrl();
            if (curUrl2.contains("plus.gov.kr/login") || applyPageText.contains("로그인 방식")) {
                logger.accept("신청폼 재인증 필요 (회원신청 후 재로그인)...");
                String mainHandle2 = driver.getWindowHandle();
                boolean idCard2 = clickIdLoginCard(driver, "신청폼재인증");
                logger.accept("재인증 아이디 로그인 카드 클릭: " + idCard2);
                // loginIdPwd 페이지 로드 대기 (최대 10초)
                waitForUrl(driver, 10000, "loginIdPwd", "loginIdPwdTo");
                Thread.sleep(500);
                dismissBrowserAlert(driver);
                // 새 창 처리
                for (String h : driver.getWindowHandles()) {
                    if (!h.equals(mainHandle2)) { driver.switchTo().window(h); break; }
                }
                saveScreenshot(driver, "gov24_relogin_form");
                logger.accept("재인증 폼 URL: " + driver.getCurrentUrl());
                // Step 1: ID 입력 → "다음" (loginIdPwd 단계에서만)
                String reLoginUrl1 = "";
                try { reLoginUrl1 = driver.getCurrentUrl(); } catch (Exception ignored) {}
                if (reLoginUrl1.contains("loginIdPwd") && !reLoginUrl1.contains("loginIdPwdTo")) {
                    WebElement loginId2 = findVisibleInput(driver, "아이디", "id", "userId", "loginId");
                    if (loginId2 == null) loginId2 = findFirstVisibleInput(driver);
                    if (loginId2 != null) {
                        loginId2.clear(); loginId2.sendKeys(id); Thread.sleep(300);
                        clickButtonByText(driver, "다음");
                        waitForUrl(driver, 8000, "loginIdPwdTo");
                        Thread.sleep(500);
                        dismissBrowserAlert(driver);
                        logger.accept("재인증 ID 후 URL: " + driver.getCurrentUrl());
                    } else {
                        logger.accept("재인증 ID 입력 필드 없음 - 직접 loginIdPwdTo 이동");
                        driver.get("https://plus.gov.kr/login/loginIdPwdTo");
                        waitForUrl(driver, 5000, "loginIdPwdTo");
                        Thread.sleep(500);
                    }
                }
                // Step 2: 비밀번호 + CAPTCHA 여부 확인
                Thread.sleep(1000);
                String rePwText = getPageText(driver);
                boolean reHasCaptcha = rePwText.contains("아래의 숫자") || rePwText.contains("보안문자")
                        || rePwText.contains("자동입력방지코드") || rePwText.contains("자동입력 방지코드");
                List<WebElement> pw2 = driver.findElements(By.cssSelector("input[type=password]"));
                for (WebElement el : pw2) {
                    if (el.isDisplayed()) { el.clear(); el.sendKeys(password); Thread.sleep(300); break; }
                }
                if (reHasCaptcha) {
                    saveScreenshot(driver, "gov24_relogin_captcha");
                    beepAlert();
                    boolean captchaOk = waitForCaptchaAndEnter(driver, "재인증");
                    if (captchaOk && !isLoggedIn(driver)) {
                        // CAPTCHA 입력 완료 (isLoggedIn으로 건너뛴 경우는 버튼 클릭 불필요)
                        clickLoginSubmitButton(driver, pw2);
                        waitForUrl(driver, 60000, "gov.kr/mw", "gov.kr/main");
                    }
                } else {
                    clickLoginSubmitButton(driver, pw2);
                    waitForUrl(driver, 30000, "gov.kr/mw", "gov.kr/main");
                }
                Thread.sleep(1500);
                dismissBrowserAlert(driver);
                // 비밀번호 변경 페이지 건너뛰기
                try {
                    String postUrl = driver.getCurrentUrl();
                    if (postUrl.contains("modifyPswd") || postUrl.contains("changePswd") || postUrl.contains("chgPswd")) {
                        logger.accept("비밀번호 변경 페이지 감지 - 건너뛰기...");
                        Boolean skipBtn = (Boolean) ((JavascriptExecutor) driver).executeScript(
                            "var els=document.querySelectorAll('button,a,input[type=button]');" +
                            "for(var i=0;i<els.length;i++){" +
                            "  var t=(els[i].textContent||els[i].value||'').replace(/\\s+/g,' ').trim();" +
                            "  var r=els[i].getBoundingClientRect();" +
                            "  if(r.width>0&&r.height>0&&(t.includes('다음에')||t.includes('나중에')||t.includes('건너뛰기')||t.includes('skip')||t.includes('취소')||t.includes('닫기'))) {" +
                            "    els[i].click(); return true;" +
                            "  }" +
                            "}" +
                            "return false;");
                        logger.accept("비밀번호 변경 건너뛰기 클릭: " + skipBtn);
                        Thread.sleep(2000);
                        dismissBrowserAlert(driver);
                        logger.accept("비밀번호 변경 후 URL: " + driver.getCurrentUrl());
                    }
                } catch (Exception e) {
                    logger.accept("비밀번호 변경 처리 오류: " + e.getMessage());
                }
                // 아직 로그인 페이지에 있으면 폼 URL로 직접 이동
                String postLoginUrl = "";
                try { postLoginUrl = driver.getCurrentUrl(); } catch (Exception ignored) {}
                if (postLoginUrl.contains("loginIdPwdTo")) {
                    // CAPTCHA 타임아웃 - 재인증 미완료
                    logger.accept("재인증 CAPTCHA 미완료 (타임아웃) - 현재 URL: " + postLoginUrl);
                    saveScreenshot(driver, "gov24_relogin_timeout");
                } else if (postLoginUrl.contains("plus.gov.kr/login") && !postLoginUrl.contains("modifyPswd")) {
                    // awqf=!2f 리다이렉트 또는 다른 로그인 페이지 - 폼 직접 이동 시도
                    logger.accept("로그인 후 폼으로 직접 이동 시도 (현재: " + postLoginUrl + ")");
                    String formUrl = "https://www.gov.kr/mw/AA040OfferMainFrm.do?capp_biz_cd=15000000098&HighCtgCD=A02004002&FAX_TYPE=B&selectedSeq=01&NEW_JUNIP=";
                    driver.get(formUrl);
                    Thread.sleep(3000);
                    dismissBrowserAlert(driver);
                    dismissPopups(driver);
                    String afterFormUrl = driver.getCurrentUrl();
                    logger.accept("직접 이동 후 URL: " + afterFormUrl);
                    // 여전히 로그인 페이지면 EgovPageLink 시도
                    if (afterFormUrl.contains("plus.gov.kr/login")) {
                        logger.accept("EgovPageLink 폼 URL로 재시도...");
                        driver.get("https://www.gov.kr/mw/EgovPageLink.do?link=minwon/AA040_std_form");
                        Thread.sleep(3000);
                        dismissBrowserAlert(driver);
                        dismissPopups(driver);
                        logger.accept("EgovPageLink 후 URL: " + driver.getCurrentUrl());
                    }
                }
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_apply_after_relogin");
                logger.accept("재인증 후 URL: " + driver.getCurrentUrl());
                applyPageText = getPageText(driver);
                logger.accept("재인증 후: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));
                hasAddressForm = formHasAddressField(applyPageText);
            }
        }

        // 아직 폼 미로드 - CappBizCD URL로 직접 이동
        if (!hasAddressForm) {
            logger.accept("신청 폼 미로드 - URL 직접 이동 시도");
            String currentUrl = driver.getCurrentUrl();
            if (currentUrl.contains("CappBizCD=")) {
                String bizCd = currentUrl.replaceAll(".*CappBizCD=([^&]+).*", "$1");
                String applyUrl = GOV24_URL + "/mw/AA020InfoCappApply.do?CappBizCD=" + bizCd;
                logger.accept("신청 직접 URL: " + applyUrl);
                driver.get(applyUrl);
                Thread.sleep(2500);
                dismissBrowserAlert(driver);
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_apply_direct");
                String afterUrl = driver.getCurrentUrl();
                applyPageText = getPageText(driver);
                logger.accept("직접이동 후 URL: " + afterUrl);
                logger.accept("직접이동 후: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));
                hasAddressForm = formHasAddressField(applyPageText);
            }
        }

        // 주소 폼 없으면 조기 종료
        if (!hasAddressForm) {
            logger.accept("주소 폼 없음 - 현재 URL: " + driver.getCurrentUrl());
            saveScreenshot(driver, "gov24_no_address_field");
            return false;
        }

        // 페이지 내 버튼 목록 디버그
        try {
            String btnDebug = (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];" +
                "document.querySelectorAll('button,input[type=button],input[type=submit],a').forEach(function(e){" +
                "  var t=(e.textContent||e.value||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=e.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length>0&&t.length<40)r.push(t);" +
                "});" +
                "return r.join('|');");
            logger.accept("폼 버튼목록: " + btnDebug);
        } catch (Exception ignored) {}

        // ── 주소구분 지번/도로명 선택 ──────────────────────────────────────────
        if ("지번".equals(addressType)) {
            try {
                Boolean jibunClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "var inputs=document.querySelectorAll('input[type=radio]');" +
                    "for(var i=0;i<inputs.length;i++){" +
                    "  var lbl='';" +
                    // 1) label[for=id] 탐색
                    "  if(inputs[i].id){" +
                    "    var lfor=document.querySelector('label[for=\"'+inputs[i].id+'\"]');" +
                    "    if(lfor) lbl=lfor.textContent||'';" +
                    "  }" +
                    // 2) labels 컬렉션
                    "  if(!lbl&&inputs[i].labels&&inputs[i].labels[0]) lbl=inputs[i].labels[0].textContent||'';" +
                    // 3) nextSibling 텍스트 노드/요소 순회
                    "  if(!lbl){var sib=inputs[i].nextSibling;while(sib){if(sib.textContent&&sib.textContent.trim()){lbl=sib.textContent;break;}sib=sib.nextSibling;}}" +
                    "  if(lbl.trim()==='지번'){inputs[i].click(); return true;}" +
                    "}" +
                    "return false;");
                logger.accept("지번 주소구분 선택: " + jibunClicked);
                Thread.sleep(600);
            } catch (Exception e) { logger.accept("지번 주소구분 선택 오류: " + e.getMessage()); }
        }

        // ── 대장구분 선택 (집합 or 일반) ──────────────────────────────────────
        try {
            Boolean gubunClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var target=arguments[0];" +
                "var inputs=document.querySelectorAll('input[type=radio]');" +
                "for(var i=0;i<inputs.length;i++){" +
                "  var lbl=inputs[i].labels&&inputs[i].labels[0]?" +
                "    inputs[i].labels[0].textContent:" +
                "    (inputs[i].nextSibling?inputs[i].nextSibling.textContent||'':'');" +
                "  if(lbl.includes(target)){inputs[i].click(); return true;}" +
                "}" +
                "return false;", daejangGubun);
            logger.accept(daejangGubun + " 대장구분 선택: " + gubunClicked);
            Thread.sleep(500);
        } catch (Exception e) { logger.accept("대장구분 선택 오류: " + e.getMessage()); }

        // 대장종류: 전유부 항상 선택
        try {
            Boolean junyubuClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var inputs=document.querySelectorAll('input[type=radio]');" +
                "for(var i=0;i<inputs.length;i++){" +
                "  var lbl=inputs[i].labels&&inputs[i].labels[0]?" +
                "    inputs[i].labels[0].textContent:" +
                "    (inputs[i].nextSibling?inputs[i].nextSibling.textContent||'':'');" +
                "  if(lbl.includes('전유부')){inputs[i].click(); return true;}" +
                "}" +
                "return false;");
            if (!Boolean.TRUE.equals(junyubuClicked)) {
                junyubuClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "var sels=document.querySelectorAll('select');" +
                    "for(var i=0;i<sels.length;i++){" +
                    "  if(!sels[i].offsetParent)continue;" +
                    "  var opts=sels[i].options;" +
                    "  for(var j=0;j<opts.length;j++){" +
                    "    if((opts[j].text||'').includes('전유부')){" +
                    "      sels[i].selectedIndex=j;" +
                    "      sels[i].dispatchEvent(new Event('change',{bubbles:true}));" +
                    "      return true;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return false;");
                logger.accept("전유부 select 선택: " + junyubuClicked);
            } else {
                logger.accept("전유부 radio 선택: true");
            }
            Thread.sleep(500);
        } catch (Exception e) { logger.accept("전유부 선택 오류: " + e.getMessage()); }
        Set<String> handlesBeforeAddr = driver.getWindowHandles();
        boolean addrBtnClicked = false;
        WebElement addrSearchBtn = null;
        // 0순위: 정확히 '검색' 텍스트인 button/a 탐색 (header의 '통합검색'과 구분)
        try {
            List<WebElement> exactSearchBtns = driver.findElements(By.xpath(
                "//button[normalize-space(.)='검색'] | " +
                "//a[normalize-space(.)='검색'] | " +
                "//input[@type='button' and normalize-space(@value)='검색']"));
            for (WebElement el : exactSearchBtns) {
                try {
                    if (el.isDisplayed()) {
                        addrSearchBtn = el;
                        logger.accept("'검색' 정확매칭 발견: " + el.getTagName() + " / " + el.getText().replace("\n"," "));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { logger.accept("'검색' 정확매칭 오류: " + e.getMessage()); }
        // 1순위: 건물번호검색 버튼 (button/a/input 모두 시도, "건물번호란?" 링크는 검색 텍스트 포함 여부로 구분)
        try {
            List<WebElement> xpathCandidates = driver.findElements(By.xpath(
                "//button[contains(normalize-space(.),'건물번호검색')] | " +
                "//a[contains(normalize-space(.),'건물번호검색')] | " +
                "//input[@type='button' and contains(@value,'건물번호검색')] | " +
                "//input[@type='submit' and contains(@value,'건물번호검색')]"));
            for (WebElement el : xpathCandidates) {
                try {
                    if (el.isDisplayed()) {
                        addrSearchBtn = el;
                        logger.accept("건물번호검색 버튼 발견: " + el.getTagName() + " / " + el.getText().replace("\n"," "));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.accept("XPath 건물번호검색 탐색 오류: " + e.getMessage());
        }
        // 2순위: "주소 검색" 관련 버튼 (button/a/input)
        if (addrSearchBtn == null) {
            try {
                List<WebElement> xpathCandidates2 = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'주소') and contains(normalize-space(.),'검색')] | " +
                    "//button[contains(normalize-space(.),'소재지')] | " +
                    "//a[contains(normalize-space(.),'소재지') and contains(normalize-space(.),'검색')] | " +
                    "//input[@type='button' and (contains(@value,'주소') or contains(@value,'소재지'))]"));
                for (WebElement el : xpathCandidates2) {
                    try { if (el.isDisplayed()) { addrSearchBtn = el; logger.accept("소재지검색 버튼 발견: " + el.getTagName() + " " + el.getText()); break; } } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // 3순위: 주소찾기/소재지번 텍스트 버튼 탐색 (button/a/input)
        if (addrSearchBtn == null) {
            try {
                List<WebElement> textBtns = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'주소찾기')] | " +
                    "//button[contains(normalize-space(.),'소재지번')] | " +
                    "//button[contains(normalize-space(.),'주소검색')] | " +
                    "//a[contains(normalize-space(.),'주소찾기')] | " +
                    "//a[contains(normalize-space(.),'소재지번')] | " +
                    "//a[contains(normalize-space(.),'주소검색')] | " +
                    "//input[@type='button' and (contains(@value,'주소찾기') or contains(@value,'소재지번') or contains(@value,'주소검색'))]"));
                for (WebElement el : textBtns) {
                    try { if (el.isDisplayed()) { addrSearchBtn = el; logger.accept("주소찾기 XPath 탐색 성공: " + el.getTagName() + " " + el.getText()); break; } } catch (Exception ignored) {}
                }
            } catch (Exception e) { logger.accept("주소찾기 XPath 오류: " + e.getMessage()); }
        }
        // 4순위: JS로 address input(readonly/disabled) 옆 검색 버튼 찾기 (절대 Y>300으로 헤더 제외)
        if (addrSearchBtn == null) {
            try {
                WebElement jsBtn = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "var scrollY=window.pageYOffset||document.documentElement.scrollTop;" +
                    "var inputs = document.querySelectorAll('input[placeholder*=\"주소\"],input[readonly]:not([type=hidden]),input[disabled]:not([type=hidden])');" +
                    "for (var i = 0; i < inputs.length; i++) {" +
                    "  var ir = inputs[i].getBoundingClientRect();" +
                    "  var absTop = ir.top + scrollY;" +
                    "  if (ir.height === 0 || absTop < 300) continue;" +
                    "  var p = inputs[i].parentElement;" +
                    "  for (var d = 0; d < 4 && p; d++, p = p.parentElement) {" +
                    "    var btns = p.querySelectorAll('button,input[type=button]');" +
                    "    for (var b = 0; b < btns.length; b++) {" +
                    "      var r = btns[b].getBoundingClientRect();" +
                    "      if (r.width > 0 && r.height > 0 && r.top+scrollY > 300) return btns[b];" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return null;");
                if (jsBtn != null) { addrSearchBtn = jsBtn; logger.accept("주소검색 버튼 JS 탐색 성공 (absY>300)"); }
            } catch (Exception e) { logger.accept("JS 주소버튼 탐색 오류: " + e.getMessage()); }
        }
        // 5순위: disabled input(주소 표시필드) 옆 버튼 탐색 - placeholder/text 비교 없이 위치만으로 탐색
        if (addrSearchBtn == null) {
            try {
                WebElement nearbyBtn = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "var scrollY=window.pageYOffset||document.documentElement.scrollTop;" +
                    "var inputs=document.querySelectorAll('input[disabled]:not([type=hidden])');" +
                    "for(var i=0;i<inputs.length;i++){" +
                    "  if(!inputs[i].placeholder)continue;" +
                    "  var ir=inputs[i].getBoundingClientRect();" +
                    "  if(ir.height===0||ir.top+scrollY<300)continue;" +
                    "  var p=inputs[i].parentElement;" +
                    "  for(var d=0;d<5&&p;d++,p=p.parentElement){" +
                    "    var btns=p.querySelectorAll('button');" +
                    "    for(var b=0;b<btns.length;b++){" +
                    "      var r=btns[b].getBoundingClientRect();" +
                    "      if(r.width>0&&r.height>0&&r.top+scrollY>300)return btns[b];" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return null;");
                if (nearbyBtn != null) { addrSearchBtn = nearbyBtn; logger.accept("disabled input 옆 버튼 발견 (5순위)"); }
            } catch (Exception e) { logger.accept("5순위 버튼 탐색 오류: " + e.getMessage()); }
        }
        String urlBeforeAddrClick = "";
        try { urlBeforeAddrClick = driver.getCurrentUrl(); } catch (Exception ignored) {}
        if (addrSearchBtn != null) {
            logger.accept("주소검색 버튼 태그/텍스트: " + addrSearchBtn.getTagName() + " / " + (addrSearchBtn.getText() + " " + safeAttr(addrSearchBtn, "value")).trim());
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", addrSearchBtn);
                Thread.sleep(300);
                addrSearchBtn.click();
                addrBtnClicked = true;
                logger.accept("주소검색 버튼 클릭 성공");
            } catch (Exception e) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", addrSearchBtn);
                    addrBtnClicked = true;
                    logger.accept("주소검색 버튼 JS클릭 성공");
                } catch (Exception e2) {
                    logger.accept("주소검색 버튼 클릭 실패: " + e2.getMessage());
                }
            }
        } else {
            logger.accept("주소검색 버튼 없음 (모든 탐색 실패)");
        }
        Thread.sleep(2000);
        dismissBrowserAlert(driver);
        String urlAfterAddrClick = "";
        try { urlAfterAddrClick = driver.getCurrentUrl(); } catch (Exception ignored) {}
        logger.accept("주소버튼 클릭 전후 URL: [" + urlBeforeAddrClick + "] → [" + urlAfterAddrClick + "]");

        // 클릭 후 URL이 바뀌었으면 잘못된 버튼 클릭 감지 (네비게이션 발생)
        if (addrBtnClicked && !urlBeforeAddrClick.equals(urlAfterAddrClick) && !urlAfterAddrClick.contains("popup") && !urlAfterAddrClick.contains("Popup")) {
            logger.accept("★ 잘못된 버튼으로 페이지 이탈 감지! URL 변경: " + urlAfterAddrClick);
            saveScreenshot(driver, "gov24_wrong_btn_navigation");
        }

        Set<String> handlesAfterAddr = driver.getWindowHandles();
        boolean newWindowOpened = handlesAfterAddr.size() > handlesBeforeAddr.size();

        if (addrBtnClicked && newWindowOpened) {
            logger.accept("주소 검색 팝업(새창) 열림");
            boolean popupOk = handleBuildingSearchPopup(driver, parts, addressType);
            if (!popupOk) {
                logger.accept("팝업 주소 선택 실패 (대장구분: " + daejangGubun + ") - 재시도 신호");
                saveScreenshot(driver, "gov24_popup_fail_" + daejangGubun);
                return false;
            }
        } else if (addrBtnClicked) {
            logger.accept("주소 검색 버튼 클릭됨 (인페이지 팝업)");
            Thread.sleep(1200);
            saveScreenshot(driver, "gov24_addr_popup_inpage");
            handleInPageAddressPopup(driver, parts);
        } else {
            logger.accept("주소검색 버튼 없음 - 직접 입력 시도");
            WebElement addrInput = findVisibleInput(driver, "주소", "addr", "address", "소재지", "지번", "로명");
            if (addrInput != null) {
                try {
                    addrInput.clear();
                    addrInput.sendKeys(parts.buildingAddress);
                    Thread.sleep(300);
                    addrInput.sendKeys(Keys.ENTER);
                    logger.accept("주소 직접 입력: " + parts.buildingAddress);
                } catch (Exception e) {
                    logger.accept("주소 직접 입력 실패 - JS로 설정: " + e.getClass().getSimpleName());
                    try {
                        ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].removeAttribute('readonly');" +
                            "arguments[0].removeAttribute('disabled');" +
                            "arguments[0].value=arguments[1];" +
                            "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));" +
                            "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                            addrInput, parts.buildingAddress);
                        logger.accept("JS로 주소 설정: " + parts.buildingAddress);
                    } catch (Exception e2) {
                        logger.accept("JS 주소 설정도 실패: " + e2.getMessage());
                    }
                }
            } else {
                logger.accept("주소 입력 필드 없음");
            }
        }
        Thread.sleep(1500);
        dismissBrowserAlert(driver);
        saveScreenshot(driver, "gov24_after_address");
        logger.accept("주소 입력 후 URL: " + driver.getCurrentUrl());
        logger.accept("주소 입력 후 버튼: " + getVisibleButtonTexts(driver));

        // ── 건물동명칭 검색 → 선택 ─────────────────────────────────────────────
        Thread.sleep(600);
        saveScreenshot(driver, "gov24_before_dong_search");
        clickBuildingDongSearchAndSelect(driver);

        // ── 호명칭 검색 → 선택 ────────────────────────────────────────────────
        if (!parts.ho.isEmpty()) {
            Thread.sleep(600);
            clickHoSearchAndSelect(driver, parts.ho);
        }

        saveScreenshot(driver, "gov24_before_submit");
        logger.accept("신청 전 버튼: " + getVisibleButtonTexts(driver));

        // 신청하기 클릭 - XPath 사용 (한글 인코딩 문제 회피)
        WebElement submitBtn = null;
        try {
            List<WebElement> submitList = driver.findElements(By.xpath(
                "//button[normalize-space(.)='신청하기'] | " +
                "//input[@type='button' and @value='신청하기'] | " +
                "//input[@type='submit' and @value='신청하기']"));
            for (WebElement el : submitList) {
                if (el.isDisplayed()) { submitBtn = el; break; }
            }
        } catch (Exception ignored) {}
        if (submitBtn == null) {
            try {
                List<WebElement> submitList2 = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'신청하기')] | " +
                    "//input[@type='button' and contains(@value,'신청')]"));
                for (WebElement el : submitList2) {
                    if (el.isDisplayed()) { submitBtn = el; break; }
                }
            } catch (Exception ignored) {}
        }
        boolean submitClicked = false;
        if (submitBtn != null) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", submitBtn);
                Thread.sleep(300);
                submitBtn.click();
                submitClicked = true;
                logger.accept("신청하기 클릭 성공 (XPath)");
            } catch (Exception e) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", submitBtn);
                    submitClicked = true;
                    logger.accept("신청하기 JS클릭 성공");
                } catch (Exception e2) {
                    logger.accept("신청하기 클릭 실패: " + e2.getMessage());
                }
            }
        } else {
            logger.accept("신청하기 버튼 없음");
        }
        logger.accept("신청하기 클릭: " + submitClicked);
        Thread.sleep(3000);
        dismissBrowserAlert(driver);
        dismissPopups(driver);
        saveScreenshot(driver, "gov24_after_submit");
        logger.accept("신청 후 URL: " + driver.getCurrentUrl());
        String submitPageText = getPageText(driver);
        logger.accept("신청 후 페이지: " + submitPageText.substring(0, Math.min(200, submitPageText.length())).replaceAll("\\s+", " "));

        // 빠른 실패 감지 (대장구분 불일치 오류)
        if (submitPageText.contains("조회 결과가 없") || submitPageText.contains("해당하는 건물") ||
            submitPageText.contains("대장이 없") || submitPageText.contains("건물이 없") ||
            submitPageText.contains("검색된 결과가 없") || submitPageText.contains("처리할 수 없")) {
            logger.accept("대장구분 오류 감지 (" + daejangGubun + "): " + submitPageText.substring(0, Math.min(80, submitPageText.length())).replaceAll("\\s+", " "));
            return false;
        }

        return downloadOrPrint(driver);
    }

    private boolean handleBuildingSearchPopup(ChromeDriver driver, AddressParts parts, String addrType) throws InterruptedException {
        String mainHandle = driver.getWindowHandle();
        Set<String> handles = driver.getWindowHandles();
        String popupHandle = mainHandle;
        for (String h : handles) { if (!h.equals(mainHandle)) { popupHandle = h; break; } }
        driver.switchTo().window(popupHandle);
        Thread.sleep(1500);
        saveScreenshot(driver, "gov24_building_popup");
        logger.accept("건물 검색 팝업 URL: " + driver.getCurrentUrl());
        logger.accept("건물 팝업 버튼: " + getVisibleButtonTexts(driver));

        boolean anyResultSelected = false;

        // ── STEP 1: 주소 구분 탭 먼저 클릭 (검색 전에 탭 선택) ──────────────
        boolean tabClicked = clickAddressTypeTab(driver, addrType);
        if (tabClicked) {
            Thread.sleep(800);
            saveScreenshot(driver, "gov24_building_popup_after_tab");
            logger.accept(addrType + " 탭 클릭 후 대기 완료");
        } else {
            logger.accept(addrType + " 탭 클릭 실패 - 기본 탭으로 계속 진행");
        }

        // ── STEP 2: 검색어 입력 ────────────────────────────────────────────
        if ("지번".equals(addrType) && !parts.jibunDong.isEmpty()) {
            boolean jibunFilled = fillJibunFields(driver, parts);
            if (!jibunFilled) {
                logger.accept("지번 별도 필드 실패 - 단일 필드 폴백");
                fillSingleSearchField(driver, parts.buildingAddress, addrType);
            }
        } else {
            fillSingleSearchField(driver, parts.buildingAddress, addrType);
        }

        // ── STEP 3: 검색 버튼 클릭 ────────────────────────────────────────
        clickPopupSearchButton(driver);
        Thread.sleep(2500);
        saveScreenshot(driver, "gov24_building_search_result");
        logger.accept("검색 후 URL: " + driver.getCurrentUrl());
        logger.accept("검색 후 버튼: " + getVisibleButtonTexts(driver));

        // ── STEP 4: 건물 결과 행 선택 ─────────────────────────────────────
        try {
            String rowClick = (String) ((JavascriptExecutor) driver).executeScript(
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick]');" +
                "for(var i=0;i<items.length;i++){" +
                "  var rc=items[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>10){items[i].click();return 'ok:'+items[i].tagName+' '+(items[i].textContent||'').trim().substring(0,40);}" +
                "}" +
                "var lnks=document.querySelectorAll('table a,.list a,.result a,tbody a');" +
                "for(var i=0;i<lnks.length;i++){" +
                "  var rc=lnks[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0){lnks[i].click();return 'ok2:'+(lnks[i].textContent||'').trim().substring(0,40);}" +
                "}" +
                "return 'not_found';");
            logger.accept("건물 행 선택: " + rowClick);

            if (rowClick != null && !rowClick.equals("not_found")) {
                anyResultSelected = true;
                Thread.sleep(1200);

                // ── STEP 5: 팝업 내 호수 선택 (지번 모드 - 건물 선택 후 호 목록 출현) ──
                if (!parts.ho.isEmpty()) {
                    boolean hoSelected = selectHoInPopup(driver, parts.ho);
                    logger.accept("팝업 내 호 선택: " + hoSelected);
                    if (hoSelected) Thread.sleep(800);
                }

                // 처리기관 2단계 선택 (해당되는 경우)
                Thread.sleep(800);
                try {
                    String agencyClick = (String) ((JavascriptExecutor) driver).executeScript(
                        "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick]');" +
                        "for(var i=0;i<items.length;i++){" +
                        "  var rc=items[i].getBoundingClientRect();" +
                        "  if(rc.width>0&&rc.height>10){items[i].click();return 'ok:'+items[i].tagName+' '+(items[i].textContent||'').trim().substring(0,30);}" +
                        "}" +
                        "return 'not_found';");
                    logger.accept("처리기관 선택(2): " + agencyClick);
                    if (agencyClick != null && !agencyClick.equals("not_found")) Thread.sleep(1500);
                } catch (Exception e2) { logger.accept("처리기관 선택(2) 오류: " + e2.getMessage()); }
            } else {
                logger.accept("건물 결과 행 없음");
            }
        } catch (Exception e) {
            logger.accept("결과 선택 오류: " + e.getMessage());
        }

        logger.accept("건물 결과 선택 완료: " + anyResultSelected);
        Thread.sleep(2000);
        saveScreenshot(driver, "gov24_after_building_select_popup");

        // 팝업 창이 자동으로 닫히길 기다리거나, 메인 창으로 복귀
        for (int w = 0; w < 10; w++) {
            Thread.sleep(500);
            try {
                if (!driver.getWindowHandles().contains(popupHandle)) { break; }
            } catch (Exception ignored) { break; }
        }
        try { driver.switchTo().window(mainHandle); } catch (Exception ignored) {}
        Thread.sleep(1000);
        dismissBrowserAlert(driver);
        saveScreenshot(driver, "gov24_after_building_select");
        logger.accept("건물 선택 후 폼 URL: " + driver.getCurrentUrl());
        logger.accept("건물 선택 후 버튼: " + getVisibleButtonTexts(driver));
        return anyResultSelected;
    }

    private boolean clickAddressTypeTab(ChromeDriver driver, String addrType) {
        // 방법1: JS exact textContent match
        try {
            String jsExact = (String) ((JavascriptExecutor) driver).executeScript(
                "var target=arguments[0];" +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){" +
                "  var el=all[i];if(el.children.length>2)continue;" +
                "  var t=(el.textContent||'').trim();" +
                "  if(t===target){" +
                "    var rc=el.getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){el.click();return 'ok:'+el.tagName+'.'+el.className;}" +
                "  }" +
                "}" +
                "return 'not_found';", addrType);
            logger.accept(addrType + " 탭 JS exact: " + jsExact);
            if (jsExact != null && jsExact.startsWith("ok:")) return true;
        } catch (Exception ignored) {}

        // 방법2: linkText
        try {
            List<WebElement> lks = driver.findElements(By.linkText(addrType));
            for (WebElement lk : lks) {
                if (lk.isDisplayed()) { lk.click(); logger.accept(addrType + " 탭 linkText 성공"); return true; }
            }
        } catch (Exception ignored) {}

        // 방법3: XPath contains
        try {
            List<WebElement> els = driver.findElements(By.xpath(
                "//*[contains(text(),'" + addrType + "') and string-length(normalize-space(.))<10]"));
            for (WebElement el : els) {
                try {
                    if (el.isDisplayed()) { el.click(); logger.accept(addrType + " 탭 XPath 성공"); return true; }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 방법4: JS includes 폴백
        try {
            String jsIncl = (String) ((JavascriptExecutor) driver).executeScript(
                "var target=arguments[0];" +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){" +
                "  var el=all[i];var t=(el.textContent||'').trim();" +
                "  if(t.indexOf(target)>=0&&t.length<10){" +
                "    var rc=el.getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){el.click();return 'ok:'+el.tagName+'.'+el.className;}" +
                "  }" +
                "}" +
                "return 'not_found';", addrType);
            logger.accept(addrType + " 탭 JS includes: " + jsIncl);
            if (jsIncl != null && jsIncl.startsWith("ok:")) return true;
        } catch (Exception ignored) {}

        return false;
    }

    private boolean fillJibunFields(ChromeDriver driver, AddressParts parts) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            List<WebElement> editables = new ArrayList<>();
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()
                            && !"true".equals(el.getAttribute("readonly"))
                            && !"true".equals(el.getAttribute("disabled"))) {
                        editables.add(el);
                    }
                } catch (Exception ignored) {}
            }
            logger.accept("지번 입력 필드 수: " + editables.size());

            if (editables.isEmpty()) return false;

            if (editables.size() == 1) {
                editables.get(0).clear();
                editables.get(0).sendKeys(parts.buildingAddress);
                logger.accept("지번 단일 필드 입력: " + parts.buildingAddress);
                return true;
            }

            // 2개 이상: placeholder/id/name으로 법정동/본번/부번 구분
            WebElement dongField = null, mainField = null, subField = null;
            for (WebElement el : editables) {
                String ph = nvl(el.getAttribute("placeholder")).toLowerCase();
                String id = nvl(el.getAttribute("id")).toLowerCase();
                String nm = nvl(el.getAttribute("name")).toLowerCase();
                String hint = ph + " " + id + " " + nm;
                if (dongField == null && (hint.contains("동") || hint.contains("dong") || hint.contains("법정"))) {
                    dongField = el;
                } else if (mainField == null && (hint.contains("본번") || hint.contains("main") || hint.contains("번지"))) {
                    mainField = el;
                } else if (subField == null && (hint.contains("부번") || hint.contains("sub") || hint.contains("산"))) {
                    subField = el;
                }
            }
            // 힌트 매칭 실패 시 순서대로 배정
            if (dongField == null) dongField = editables.get(0);
            if (mainField == null && editables.size() >= 2) mainField = editables.get(1);
            if (subField == null && editables.size() >= 3) subField = editables.get(2);

            dongField.clear(); dongField.sendKeys(parts.jibunDong);
            logger.accept("법정동 입력: " + parts.jibunDong);
            if (mainField != null && !parts.jibunMain.isEmpty()) {
                mainField.clear(); mainField.sendKeys(parts.jibunMain);
                logger.accept("본번 입력: " + parts.jibunMain);
            }
            if (subField != null && !"0".equals(parts.jibunSub) && !parts.jibunSub.isEmpty()) {
                subField.clear(); subField.sendKeys(parts.jibunSub);
                logger.accept("부번 입력: " + parts.jibunSub);
            }
            return true;
        } catch (Exception e) { logger.accept("지번 필드 입력 오류: " + e.getMessage()); }
        return false;
    }

    private void fillSingleSearchField(ChromeDriver driver, String searchTerm, String addrType) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()
                            && !"true".equals(el.getAttribute("readonly"))
                            && !"true".equals(el.getAttribute("disabled"))) {
                        el.clear(); el.sendKeys(searchTerm);
                        logger.accept("검색어 입력: " + searchTerm + " [" + addrType + "]");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            logger.accept("검색 입력 필드 없음");
        } catch (Exception e) { logger.accept("단일 필드 입력 오류: " + e.getMessage()); }
    }

    private void clickPopupSearchButton(ChromeDriver driver) {
        try {
            List<WebElement> searchBtns = driver.findElements(By.xpath(
                "//button[normalize-space(.)='검색'] | " +
                "//input[@type='button' and normalize-space(@value)='검색'] | " +
                "//input[@type='submit' and normalize-space(@value)='검색']"));
            for (WebElement sb : searchBtns) {
                if (sb.isDisplayed()) { sb.click(); logger.accept("검색 버튼 클릭"); return; }
            }
        } catch (Exception ignored) {}
        // fallback: 편집 가능한 첫 번째 입력 필드에 ENTER
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()) {
                        el.sendKeys(Keys.ENTER); logger.accept("검색버튼 없음 - ENTER 사용"); return;
                    }
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}
        logger.accept("검색 버튼 클릭 실패");
    }

    private boolean selectHoInPopup(ChromeDriver driver, String ho) {
        // select 드롭다운에서 호 선택
        try {
            List<WebElement> selects = driver.findElements(By.tagName("select"));
            for (WebElement sel : selects) {
                try {
                    if (!sel.isDisplayed()) continue;
                    org.openqa.selenium.support.ui.Select selectEl = new org.openqa.selenium.support.ui.Select(sel);
                    for (WebElement opt : selectEl.getOptions()) {
                        if (opt.getText().replaceAll("[^0-9]", "").equals(ho)) {
                            selectEl.selectByVisibleText(opt.getText());
                            logger.accept("팝업 호 드롭다운 선택: " + opt.getText());
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        // 클릭 가능한 행/링크에서 호 번호 매칭
        try {
            String hoClick = (String) ((JavascriptExecutor) driver).executeScript(
                "var ho=arguments[0];" +
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var t=(items[i].textContent||'').trim();" +
                "  if(t.includes(ho+'호')||t.includes(ho+' 호')){" +
                "    var rc=items[i].getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){items[i].click();return 'ok:'+t.substring(0,30);}" +
                "  }" +
                "}" +
                "return 'not_found';", ho);
            logger.accept("팝업 호 클릭: " + hoClick);
            return hoClick != null && hoClick.startsWith("ok:");
        } catch (Exception e) { logger.accept("팝업 호 선택 오류: " + e.getMessage()); }
        return false;
    }

    private void handleInPageAddressPopup(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        // readonly/disabled 제외한 편집 가능한 입력 필드만 수집
        List<WebElement> editableInputs = getEditableInputs(driver);
        logger.accept("인페이지 팝업 편집가능 필드 수: " + editableInputs.size());

        if ("지번".equals(addressType)) {
            // 지번 탭 클릭 시도 (팝업 내)
            clickAddressTypeTab(driver, "지번");
            Thread.sleep(500);
            // 탭 클릭 후 재수집
            editableInputs = getEditableInputs(driver);
            logger.accept("탭 클릭 후 편집가능 필드 수: " + editableInputs.size());

            if (editableInputs.size() >= 2) {
                WebElement beonjieField = null, hoField = null;
                for (WebElement el : editableInputs) {
                    String hint = (nvl(el.getAttribute("placeholder")) + " " + nvl(el.getAttribute("id")) + " " + nvl(el.getAttribute("name"))).toLowerCase();
                    if (beonjieField == null && (hint.contains("번지") || hint.contains("bon") || hint.contains("main") || hint.contains("num"))) {
                        beonjieField = el;
                    } else if (hoField == null && (hint.contains("호") && !hint.contains("번지"))) {
                        hoField = el;
                    }
                }
                if (beonjieField == null) beonjieField = editableInputs.get(0);
                if (hoField == null && editableInputs.size() >= 2) hoField = editableInputs.get(1);

                try { beonjieField.clear(); beonjieField.sendKeys(parts.jibunMain); logger.accept("번지 입력: " + parts.jibunMain); } catch (Exception e) { logger.accept("번지 입력 오류: " + e.getMessage()); }
                if (hoField != null && !"0".equals(parts.jibunSub) && !parts.jibunSub.isEmpty()) {
                    try { hoField.clear(); hoField.sendKeys(parts.jibunSub); logger.accept("부번 입력: " + parts.jibunSub); } catch (Exception e) { logger.accept("부번 입력 오류: " + e.getMessage()); }
                }
            } else if (!editableInputs.isEmpty()) {
                try { editableInputs.get(0).clear(); editableInputs.get(0).sendKeys(parts.jibunMain); logger.accept("번지 단일 입력: " + parts.jibunMain); } catch (Exception e) { logger.accept("번지 단일 입력 오류: " + e.getMessage()); }
            } else {
                logger.accept("인페이지 팝업 입력 필드 없음");
                return;
            }
        } else {
            if (!editableInputs.isEmpty()) {
                try { editableInputs.get(0).clear(); editableInputs.get(0).sendKeys(parts.buildingAddress); logger.accept("도로명 팝업 입력: " + parts.buildingAddress); } catch (Exception e) { logger.accept("도로명 팝업 입력 오류: " + e.getMessage()); }
            } else {
                logger.accept("도로명 팝업 입력 필드 없음");
                return;
            }
        }

        clickPopupSearchButton(driver);
        Thread.sleep(2000);
        saveScreenshot(driver, "gov24_addr_popup_result");

        // 결과 행 선택 (onclick 속성 행 또는 tbody a 링크 우선 — 헤더/네비 tr 제외)
        try {
            ((JavascriptExecutor) driver).executeScript(
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var r=items[i].getBoundingClientRect();" +
                "  if(r.width>0&&r.height>0){items[i].click();return;}" +
                "}");
            logger.accept("인페이지 팝업 결과 선택 완료");
            Thread.sleep(1200);
        } catch (Exception e) {
            logger.accept("인페이지 팝업 결과 선택 오류: " + e.getMessage());
        }
    }

    private List<WebElement> getEditableInputs(ChromeDriver driver) {
        List<WebElement> result = new ArrayList<>();
        try {
            List<WebElement> all = driver.findElements(By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : all) {
                try {
                    if (!el.isDisplayed() || !el.isEnabled()) continue;
                    String ro = el.getAttribute("readonly");
                    String dis = el.getAttribute("disabled");
                    if ("true".equalsIgnoreCase(ro) || "readonly".equalsIgnoreCase(ro)) continue;
                    if ("true".equalsIgnoreCase(dis) || "disabled".equalsIgnoreCase(dis)) continue;
                    result.add(el);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void clickBuildingDongSearchAndSelect(ChromeDriver driver) throws InterruptedException {
        try {
            // 건물동명칭/호명칭 검색 버튼은 <button>태그, 기본주소 검색은 <input type="button"> — button만 선택
            List<WebElement> btns = driver.findElements(By.xpath("//button[normalize-space(.)='검색']"));
            for (WebElement btn : btns) {
                if (!btn.isDisplayed()) continue;
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
                Thread.sleep(200);
                btn.click();
                logger.accept("건물동명칭 검색 클릭");
                break;
            }
        } catch (Exception e) { logger.accept("건물동명칭 검색 클릭 오류: " + e.getMessage()); return; }
        Thread.sleep(1000);
        saveScreenshot(driver, "gov24_dong_search_result");
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var rc=items[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0){items[i].click();return 'ok:'+(items[i].textContent||'').trim().substring(0,20);}" +
                "}" +
                "return 'not_found';");
            logger.accept("건물동명칭 결과 선택: " + result);
            Thread.sleep(800);
        } catch (Exception e) { logger.accept("건물동명칭 결과 선택 오류: " + e.getMessage()); }
    }

    private void clickHoSearchAndSelect(ChromeDriver driver, String ho) throws InterruptedException {
        try {
            // <button>태그 검색 버튼만 — 첫 번째=건물동명칭, 두 번째=호명칭
            List<WebElement> btns = driver.findElements(By.xpath("//button[normalize-space(.)='검색']"));
            boolean skipFirst = true;
            for (WebElement btn : btns) {
                if (!btn.isDisplayed()) continue;
                if (skipFirst) { skipFirst = false; continue; } // 건물동명칭용 첫 검색 버튼 건너뜀
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
                Thread.sleep(200);
                btn.click();
                logger.accept("호명칭 검색 클릭");
                break;
            }
        } catch (Exception e) { logger.accept("호명칭 검색 클릭 오류: " + e.getMessage()); return; }
        Thread.sleep(1000);
        saveScreenshot(driver, "gov24_ho_search_result");
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var ho=arguments[0];" +
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var t=(items[i].textContent||'').trim();" +
                "  var rc=items[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&(t===ho+'호'||t.includes(ho+'호'))){items[i].click();return 'ok:'+t.substring(0,20);}" +
                "}" +
                "for(var i=0;i<items.length;i++){" +
                "  var rc=items[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0){items[i].click();return 'fallback:'+(items[i].textContent||'').trim().substring(0,20);}" +
                "}" +
                "return 'not_found';", ho);
            logger.accept("호명칭 결과 선택: " + result);
            Thread.sleep(800);
        } catch (Exception e) { logger.accept("호명칭 결과 선택 오류: " + e.getMessage()); }
    }

    private String nvl(String s) { return s != null ? s : ""; }

    private String getVisibleButtonTexts(ChromeDriver driver) {
        try {
            return (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];document.querySelectorAll('button,input[type=button],input[type=submit],a').forEach(function(e){" +
                "  var t=(e.textContent||e.value||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=e.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length>0&&t.length<30)r.push(t);" +
                "});return r.slice(0,20).join('|');");
        } catch (Exception e) { return ""; }
    }

    // ─── 팝업 / 주소 팝업 ────────────────────────────────────────────────

    /** position:fixed 요소 포함하여 닫기 버튼 클릭. offsetParent 대신 getBoundingClientRect 사용. */
    private void dismissPopups(ChromeDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "document.querySelectorAll('button, a').forEach(function(b) {" +
                "  var rect = b.getBoundingClientRect();" +
                "  if (rect.width === 0 && rect.height === 0) return;" +
                "  var t = (b.innerText || b.textContent || '').trim();" +
                "  if (t === '닫기' || t === '확인' || t === '×' || t === 'X' || t === 'close' || t === '오늘 하루 보지 않기') {" +
                "    b.click();" +
                "  }" +
                "});");
            Thread.sleep(400);
            try { driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void handleAddressPopup(ChromeDriver driver, String address) throws InterruptedException {
        String mainHandle = driver.getWindowHandle();
        Set<String> handles = driver.getWindowHandles();
        String popupHandle = mainHandle;
        for (String h : handles) { if (!h.equals(mainHandle)) { popupHandle = h; break; } }
        driver.switchTo().window(popupHandle);

        WebElement input = findFirstVisibleInput(driver);
        if (input != null) {
            input.clear();
            input.sendKeys(address);
            input.sendKeys(Keys.ENTER);
            Thread.sleep(2000);
        }
        ((JavascriptExecutor) driver).executeScript(
            "var rows = document.querySelectorAll('tr, li, .result');" +
            "for (var i = 0; i < rows.length; i++) {" +
            "  var links = rows[i].querySelectorAll('a, button');" +
            "  var rect = links.length > 0 ? links[0].getBoundingClientRect() : null;" +
            "  if (rect && rect.width > 0) { links[0].click(); return; }" +
            "}");
        Thread.sleep(1000);
        driver.switchTo().window(mainHandle);
    }

    // ─── 다운로드 ────────────────────────────────────────────────────────

    private boolean downloadOrPrint(ChromeDriver driver) throws InterruptedException {
        saveScreenshot(driver, "05_before_download");
        logger.accept("다운로드/출력 버튼 탐색 중... URL: " + driver.getCurrentUrl());
        String curUrl0 = driver.getCurrentUrl();
        // 최대 90초 대기 (페이지 로딩 포함)
        long end = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < end) {
            String pageText = getPageText(driver);
            String curUrl1 = "";
            try { curUrl1 = driver.getCurrentUrl(); } catch (Exception ignored) {}
            // 결과 페이지 판단: 서비스 개요 페이지(신청방법/신청자격)는 제외
            boolean isServiceOverview = pageText.contains("신청방법") && pageText.contains("신청자격") && pageText.contains("서비스 개요");
            boolean onResultPage = !isServiceOverview && (
                pageText.contains("접수번호") || pageText.contains("처리결과") || pageText.contains("발급문서")
                || pageText.contains("민원결과") || pageText.contains("대장정보") || pageText.contains("문서확인")
                || pageText.contains("신청이 완료") || pageText.contains("접수가 완료")
                || curUrl1.contains("IssueResult") || curUrl1.contains("Result.do") || curUrl1.contains("result"));
            logger.accept("결과페이지여부=" + onResultPage + " (URL=" + curUrl1.substring(Math.max(0, curUrl1.length()-60)) + ")");
            if (onResultPage) {
                saveScreenshot(driver, "gov24_result_page");
                // XPath로 출력/다운로드 버튼 탐색
                WebElement dlBtn = null;
                try {
                    List<WebElement> candidates = driver.findElements(By.xpath(
                        "//button[contains(normalize-space(.),'출력')] | " +
                        "//button[contains(normalize-space(.),'PDF')] | " +
                        "//button[contains(normalize-space(.),'다운')] | " +
                        "//button[contains(normalize-space(.),'저장')] | " +
                        "//button[contains(normalize-space(.),'문서확인')] | " +
                        "//button[contains(normalize-space(.),'문서 확인')] | " +
                        "//a[contains(normalize-space(.),'출력')] | " +
                        "//a[contains(normalize-space(.),'PDF')] | " +
                        "//a[contains(normalize-space(.),'다운')] | " +
                        "//input[@type='button' and (contains(@value,'출력') or contains(@value,'PDF') or contains(@value,'저장'))]"));
                    for (WebElement el : candidates) {
                        if (el.isDisplayed()) { dlBtn = el; break; }
                    }
                } catch (Exception ignored) {}
                if (dlBtn != null) {
                    logger.accept("출력/다운로드 버튼 발견: " + dlBtn.getText().trim());
                    try {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", dlBtn);
                        Thread.sleep(300);
                        dlBtn.click();
                    } catch (Exception e) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", dlBtn);
                    }
                    logger.accept("클릭 완료, 다운로드 대기...");
                    Thread.sleep(3000);
                    // 인쇄 다이얼로그 처리: Ctrl+P 무시, 대신 PDF 저장 핸들링
                    dismissBrowserAlert(driver);
                    // 새 창/탭에서 PDF가 열렸는지 확인
                    String mainHandle = driver.getWindowHandle();
                    Set<String> handles = driver.getWindowHandles();
                    for (String h : handles) {
                        if (!h.equals(mainHandle)) {
                            driver.switchTo().window(h);
                            Thread.sleep(1000);
                            String newUrl = driver.getCurrentUrl();
                            logger.accept("새 탭 URL: " + newUrl);
                            saveScreenshot(driver, "gov24_download_tab");
                            if (newUrl.contains(".pdf") || newUrl.contains("PDF") || newUrl.contains("download")) {
                                logger.accept("PDF 탭 감지됨");
                            }
                            break;
                        }
                    }
                    try { driver.switchTo().window(mainHandle); } catch (Exception ignored) {}
                    waitForNewFile();
                    logger.accept("건축물대장 다운로드 완료.");
                    return true;
                }
            }
            // 신청 완료 후 문서 조회 페이지 탐색
            if (pageText.contains("신청이 완료") || pageText.contains("접수 완료") || pageText.contains("문서를 열람")) {
                logger.accept("신청완료 페이지 감지 - 문서 조회 클릭 시도");
                saveScreenshot(driver, "gov24_apply_complete");
                try {
                    List<WebElement> viewBtns = driver.findElements(By.xpath(
                        "//button[contains(normalize-space(.),'문서')] | " +
                        "//button[contains(normalize-space(.),'확인')] | " +
                        "//button[contains(normalize-space(.),'조회')] | " +
                        "//a[contains(normalize-space(.),'문서')] | " +
                        "//a[contains(normalize-space(.),'확인')]"));
                    for (WebElement el : viewBtns) {
                        if (el.isDisplayed()) { el.click(); Thread.sleep(2000); break; }
                    }
                } catch (Exception ignored) {}
            }
            Thread.sleep(1000);
        }
        saveScreenshot(driver, "gov24_download_failed");
        logger.accept("다운로드 버튼 자동 탐색 실패. 스크린샷 저장됨.");
        logger.accept("현재 URL: " + driver.getCurrentUrl());
        logger.accept("현재 버튼: " + getVisibleButtonTexts(driver));
        Thread.sleep(5000);
        return false;
    }

    private void waitForNewFile() throws InterruptedException {
        String[] before = new File(savePath).list();
        Set<String> beforeSet = before != null ? new HashSet<>(Arrays.asList(before)) : new HashSet<>();
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1000);
            String[] current = new File(savePath).list();
            if (current != null) {
                for (String f : current) {
                    if (!beforeSet.contains(f) && (f.endsWith(".pdf") || f.endsWith(".PDF"))) {
                        logger.accept("저장됨: " + f);
                        return;
                    }
                }
            }
        }
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private void clickLoginSubmitButton(ChromeDriver driver, List<WebElement> pwInputs) {
        // Use XPath to find <button> submit only — avoids nav "로그인" <a> link
        try {
            List<WebElement> btns = driver.findElements(By.xpath(
                "//button[normalize-space(text())='로그인'] | //input[@type='submit' and @value='로그인'] | //input[@type='button' and @value='로그인']"));
            for (WebElement b : btns) {
                if (b.isDisplayed()) { b.click(); return; }
            }
        } catch (Exception ignored) {}
        // Fallback: Enter on password field
        if (pwInputs != null) {
            for (WebElement el : pwInputs) {
                try { if (el.isDisplayed()) { el.sendKeys(Keys.ENTER); return; } } catch (Exception ignored) {}
            }
        }
    }

    private void waitForUrl(ChromeDriver driver, int maxMs, String... urlParts) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            try {
                String url = driver.getCurrentUrl();
                for (String part : urlParts) {
                    if (url.contains(part)) return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(300);
        }
    }

    private void beepAlert() {
        try {
            java.awt.Toolkit.getDefaultToolkit().beep(); Thread.sleep(300);
            java.awt.Toolkit.getDefaultToolkit().beep(); Thread.sleep(300);
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Exception ignored) {}
    }

    private boolean waitForCaptchaAndEnter(ChromeDriver driver, String tag) throws InterruptedException {
        File captchaValueFile = new File(savePath + File.separator + "captcha_value.txt");
        File captchaFlagFile = new File(savePath + File.separator + "captcha_needed.flag");
        captchaValueFile.delete();
        try { new java.io.FileOutputStream(captchaFlagFile).close(); } catch (Exception e) {}
        saveScreenshot(driver, "captcha_screenshot");
        logger.accept("[" + tag + "] ★ CAPTCHA 스크린샷: dbg_captcha_screenshot.png ★");
        logger.accept("[" + tag + "] ★ captcha_value.txt 에 값 입력 대기... ★");
        for (int w = 0; w < 450; w++) {
            Thread.sleep(2000);
            // 이미 로그인 완료된 경우 즉시 탈출
            if (isLoggedIn(driver)) {
                logger.accept("[" + tag + "] 이미 로그인 완료 - CAPTCHA 건너뜀");
                captchaFlagFile.delete();
                captchaValueFile.delete();
                return true;
            }
            if (captchaValueFile.exists()) {
                String val = "";
                try {
                    val = new String(java.nio.file.Files.readAllBytes(captchaValueFile.toPath())).trim();
                    if (val.startsWith("﻿")) val = val.substring(1); // UTF-8 BOM 제거
                    val = val.replaceAll("[^0-9a-zA-Z]", ""); // 숫자/영문만 남기기
                } catch (Exception e) { val = ""; }
                // 파일은 반드시 삭제 (예외와 무관)
                captchaFlagFile.delete();
                captchaValueFile.delete();
                if (!val.isEmpty()) {
                    logger.accept("[" + tag + "] CAPTCHA 값 수신: " + val);
                    try {
                        List<WebElement> textInputs = driver.findElements(By.cssSelector("input[type=text]"));
                        for (WebElement el : textInputs) {
                            try {
                                if (el.isDisplayed() && el.isEnabled()
                                        && !"true".equalsIgnoreCase(el.getAttribute("readonly"))) {
                                    el.clear();
                                    el.sendKeys(val);
                                    logger.accept("[" + tag + "] CAPTCHA 입력 완료");
                                    return true;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        logger.accept("[" + tag + "] CAPTCHA 입력 오류: " + e.getMessage());
                    }
                    return false;
                }
            }
            if (w > 0 && w % 15 == 0) {
                try { saveScreenshot(driver, "captcha_screenshot"); } catch (Exception ignored) {}
                logger.accept("[" + tag + "] CAPTCHA 대기 중... (" + (w * 2 / 60) + "분)");
            }
        }
        captchaFlagFile.delete();
        logger.accept("[" + tag + "] CAPTCHA 대기 타임아웃");
        return false;
    }

    /** 실제 "로그아웃" 링크/버튼이 화면에 보이는 경우만 로그인으로 판단 */
    private boolean isLoggedIn(WebDriver driver) {
        try {
            Boolean result = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var els=document.querySelectorAll('a,button');" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=els[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&(t==='로그아웃'||t==='로그 아웃'))return true;" +
                "}" +
                "return false;");
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForManualLogin(ChromeDriver driver) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            if (isLoggedIn(driver)) return true;
        }
        logger.accept("로그인 대기 시간 초과 (5분) - 다운로드 종료.");
        return false;
    }

    private WebElement findVisibleInput(ChromeDriver driver, String... hints) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text], input[type=search], input:not([type])"));
            for (WebElement el : inputs) {
                if (!el.isDisplayed()) continue;
                String ph = safeAttr(el, "placeholder").toLowerCase();
                String id2 = safeAttr(el, "id").toLowerCase();
                String name = safeAttr(el, "name").toLowerCase();
                for (String hint : hints) {
                    String h = hint.toLowerCase();
                    if (ph.contains(h) || id2.contains(h) || name.contains(h)) return el;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private WebElement findFirstVisibleInput(ChromeDriver driver) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text], input[type=search], input:not([type])"));
            for (WebElement el : inputs) { if (el.isDisplayed()) return el; }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean clickLinkByText(ChromeDriver driver, String... texts) {
        try {
            List<WebElement> els = driver.findElements(By.cssSelector("a, button"));
            for (WebElement el : els) {
                if (!el.isDisplayed()) continue;
                String t = el.getText().trim();
                for (String text : texts) {
                    if (t.equals(text) || t.contains(text)) { el.click(); return true; }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean clickButtonByText(ChromeDriver driver, String... texts) {
        try {
            List<WebElement> els = driver.findElements(
                By.cssSelector("button, a, input[type=submit], input[type=button]"));
            for (WebElement el : els) {
                if (!el.isDisplayed()) continue;
                String t = (el.getText() + " " + safeAttr(el, "value")).trim();
                for (String text : texts) {
                    if (t.equals(text) || t.contains(text)) { el.click(); return true; }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String safeAttr(WebElement el, String attr) {
        try { String v = el.getAttribute(attr); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    private void waitForText(ChromeDriver driver, int maxMs, String... texts) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            try {
                String pageText = getPageText(driver);
                for (String t : texts) { if (pageText.contains(t)) return; }
            } catch (Exception ignored) {}
            Thread.sleep(400);
        }
    }

    private String getPageText(WebDriver driver) {
        try {
            Object r = ((JavascriptExecutor) driver)
                .executeScript("return document.body ? (document.body.innerText || '') : ''");
            return r != null ? String.valueOf(r) : "";
        } catch (Exception e) { return ""; }
    }

    void saveScreenshot(ChromeDriver driver, String name) {
        try {
            File src = driver.getScreenshotAs(OutputType.FILE);
            File dest = new File(savePath + File.separator + "dbg_" + name + ".png");
            dest.getParentFile().mkdirs();
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.accept("스크린샷: " + dest.getName());
        } catch (Exception e) {
            logger.accept("스크린샷 실패: " + e.getMessage());
        }
    }

    // ─── 주소 파싱 ────────────────────────────────────────────────────────

    static class AddressParts {
        String buildingAddress; // 팝업 검색어 (동호수 제외)
        String dong;            // 아파트 동 번호 (203)
        String ho;              // 아파트 호 번호 (101)
        String jibunDong;       // 지번 법정동 이름 (방이동, 공세동)
        String jibunMain;       // 지번 본번 (42, 714)
        String jibunSub;        // 지번 부번 (1, 0)
    }

    private AddressParts parseAddress(String address) {
        AddressParts p = new AddressParts();
        // 동호 추출 (X동 Y호)
        Matcher dongHo = Pattern.compile("(\\d+)동\\s*(\\d+)호").matcher(address);
        if (dongHo.find()) {
            p.dong = dongHo.group(1);
            p.ho = dongHo.group(2);
            p.buildingAddress = address.substring(0, dongHo.start()).trim();
        } else {
            Matcher ho = Pattern.compile("(\\d+)호").matcher(address);
            if (ho.find()) {
                p.ho = ho.group(1);
                p.dong = "";
                p.buildingAddress = address.substring(0, ho.start()).trim();
            } else {
                p.dong = "";
                p.ho = "";
                p.buildingAddress = address.trim();
            }
        }
        // 지번 파싱: buildingAddress에서 "법정동 본번-부번" 추출
        // 예) "방이동 42-1" → jibunDong=방이동, jibunMain=42, jibunSub=1
        // 예) "공세동 714" → jibunDong=공세동, jibunMain=714, jibunSub=0
        Matcher jibun = Pattern.compile("^(.+?)\\s+(\\d+)(?:-(\\d+))?\\s*$").matcher(p.buildingAddress);
        if (jibun.matches()) {
            p.jibunDong = jibun.group(1).trim();
            p.jibunMain = jibun.group(2);
            p.jibunSub  = jibun.group(3) != null ? jibun.group(3) : "0";
        } else {
            p.jibunDong = p.buildingAddress;
            p.jibunMain = "";
            p.jibunSub  = "0";
        }
        return p;
    }
}
