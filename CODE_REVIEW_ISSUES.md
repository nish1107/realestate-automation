# 코드 리뷰 이슈 목록

작성일: 2026-07-02  
대상: realestate-automation Java Selenium 프로젝트

---

## 🔴 HIGH PRIORITY (기능 버그)

### ~~1. Gov24Automation.java — tryEais / doEaisLogin / searchEais 데드코드~~ ✅ 수정완료
- `tryEais()`, `doEaisLogin()`, `searchEais()`, `EAIS_URL` 상수 삭제 (272줄 제거)
- 클래스 Javadoc 세움터 → 정부24 로 업데이트

### ~~2. Gov24Automation.java — downloadOrPrint() PDF 실패 시에도 true 반환~~ ✅ 수정완료
- viewerHandle 열렸으나 pdfSaved=false 인 경우 RuntimeException throw 추가
- 정상 경로(pdfSaved=true) 및 viewerHandle=null(브라우저 자동다운로드) 경로 변경 없음

### ~~3. Gov24Automation.java — handleInPageAddressPopup Pass 2 서울 하드코딩~~ ✅ 수정완료
- Pass 1/Pass 2 모두 `서울&&송파` 하드코드 제거
- `jd`(jibunDong) + `st`(searchTerm=전체검색어) OR 조건으로 교체
- `searchTerm`을 JS arguments[1]로 추가 전달

### ~~4. IrosAutomation.java / LandRegisterAutomation.java — Chrome 락파일 정리 없음~~ ✅ 수정완료
- IrosAutomation: `/iros-chrome-profile` 에 락파일 정리 추가
- LandRegisterAutomation: `/gov24-chrome-profile` 에 락파일 정리 추가 (동반 수정)

### 5. IrosAutomation.java — extractSido() 폴백 `-전체-` 드롭다운 불일치
- **위치**: line ~1072, `extractSido()` 메서드
- **문제**: 인식되지 않는 주소에서 `-전체-` 로 폴백하는데, IROS 드롭다운에 해당 옵션이 없으면 예외 발생
- **영향**: 비표준 주소 등기부등본 다운로드 실패

---

## 🟡 MEDIUM PRIORITY (엣지케이스)

### ~~6. Gov24Automation.java — dismissPopups() 무차별 버튼 클릭~~ ⏭ 스킵
- 호출 위치가 로그인/진입 직후로만 한정되어 실제 문제 미발생

### ~~7. Gov24Automation.java — waitForNewFile() 레이스 컨디션~~ ✅ 수정완료
- 스냅샷을 `printBtn.click()` 이전으로 이동, `waitForNewFile(Set<String>)`으로 전달
- 1순위(CDP PDF) 경로 영향 없음 — 2순위(인쇄 버튼) 경로만 수정

### 8. Gov24Automation.java — clickBuildingDongSearchAndSelect() 항상 첫 번째 동 선택
- **위치**: `clickBuildingDongSearchAndSelect()`, line ~2020
- **문제**: 항상 첫 번째 `button.list-btn` 을 클릭함. `parts.dong` 값과 실제 매칭하지 않음
- **영향**: 다동 건물에서 잘못된 동 선택 가능

### 9. Gov24Automation.java — tryGov24() 집합/일반 모두 실패 시 로그 없음
- **위치**: `tryGov24()`, lines ~543–546
- **문제**: 집합건물 폼과 일반건물 폼 모두 `fillBuildingForm` 실패 시 최종 실패 로그가 없음
- **영향**: 디버깅 시 실패 원인 파악 어려움

### 10. Gov24Automation.java — handleBuildingSearchPopup() 성공 검증 없이 true 반환
- **위치**: `handleBuildingSearchPopup()` 메서드
- **문제**: `anyResultSelected=true` 를 반환하지만 실제로 주소 필드가 채워졌는지 확인하지 않음
- **영향**: 팝업 처리 실패를 성공으로 오인할 수 있음

---

## 🔵 LOW PRIORITY (코드 품질)

### 11. AppConfig.java — isConfigured() 미사용 데드코드
- **위치**: `AppConfig.isConfigured()` 메서드
- **문제**: `MainController` 에서 한 번도 호출되지 않는 데드코드. 게다가 `serve.id` 를 검사하는데 serve 는 별도 서비스임
- **영향**: 혼란 초래, 없애도 무방

### 12. MainController.java — ExecutorService 종료 안 됨
- **위치**: line 41, `Executors.newSingleThreadExecutor()`
- **문제**: 앱 종료 시 `executor.shutdown()` 호출 없음. `stage.setOnCloseRequest` 등으로 처리 필요
- **영향**: JVM이 정상 종료되지 않고 스레드가 남을 수 있음

### 13. Gov24Automation.java — CAPTCHA 대기 시간 불일치
- **문제**: `waitForCaptchaAndEnter` = 15분 vs `waitForManualLogin` = 5분으로 일관성 없음
- **영향**: UX 혼란 (어떤 상황에서 얼마나 기다려야 하는지 불명확)

### 14. MainApp.java — GUI 윈도우 크기 하드코딩
- **위치**: `MainApp.java` line 13, `MainController.java` `setResizable(false)`
- **문제**: `Scene` 크기 500x550 하드코딩, 리사이즈 불가
- **영향**: 고DPI 환경이나 텍스트 크기 설정에 따라 UI 잘림 가능

---

## 참고: 파일별 위치

| 파일 | 관련 이슈 |
|------|----------|
| `Gov24Automation.java` | 1, 2, 3, 6, 7, 8, 9, 10, 13 |
| `IrosAutomation.java` | 4, 5 |
| `AppConfig.java` | 11 |
| `MainController.java` | 12 |
| `MainApp.java` | 14 |
