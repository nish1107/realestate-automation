# 부동산 자동화 - 타이밍/속도 문제 수정 작업 목록

## 작업 상태
- 파일 경로: `C:\Users\FAMILY\realestate-automation`
- 주요 파일:
  - `src/main/java/com/realestateauto/automation/IrosAutomation.java` (IROS 등기부등본)
  - `src/main/java/com/realestateauto/automation/Gov24Automation.java` (건축물대장)
- 빌드: `cd C:\Users\FAMILY\realestate-automation && .\gradlew testRun` (TestRun 실행)
- 인코딩 주의: IrosAutomation.java는 CRLF+혼합인코딩 → Edit 도구 사용 불가, PowerShell로만 수정
- 현재 git HEAD: 확인 필요 (`git log --oneline -5`)

---

## 수정 우선순위 목록

### [P1] IROS - 다음(1)/(2) 클릭 후 화면 전환 확인 없음
- **파일**: IrosAutomation.java
- **위치**: `searchAndDownload` 메서드, 약 529줄(다음1), 551줄(다음2)
- **문제**: `waitForPageChange`가 void 반환 → 타임아웃 후 페이지 안 바뀌어도 다음 단계 진행
- **증상**: 소재지번 화면 아닌 곳에서 selectResultByUnit 실행, 또는 다음(2) 후 발급신청 화면 아닌 곳에서 handleIssuancePage 실행
- **수정 방향**:
  - `waitForPageChange`를 boolean 반환형으로 변경하거나
  - 호출 후 현재 페이지 텍스트에서 "소재지번" 또는 "발급 신청" 키워드 확인
  - 확인 실패 시 최대 2회 재시도
- **상태**: [x] 완료 - "소재지번"/"발급" 키워드 폴링 + 최대 2회 재클릭 추가

### [P2] IROS - 차=0(검색 결과 없음) 케이스에서 검색 재실행 없음
- **파일**: IrosAutomation.java
- **위치**: `searchAndDownload` → `executeSearchAndSelect` 호출부, 약 507줄
- **문제**: IROS가 검색 결과를 아예 안 돌려줄 때(차=0) 재시도해도 빈 그리드만 읽음. 검색 자체를 다시 실행해야 함
- **수정 방향**: executeSearchAndSelect 내부에서 검색 후 차=0이면 검색 버튼 재클릭(최대 2회)
- **상태**: [x] 완료 - dismissExistingCartPopup 블록 이후 차=0 체크 + ENTER 재실행 최대 2회 추가

### [P3] Gov24 - 동/호명칭 팝업 창 열리기를 1.5초 고정으로 판단
- **파일**: Gov24Automation.java
- **위치**: `clickBuildingDongSearchAndSelect` (약 1783줄), `clickHoSearchAndSelect` (약 1928줄)
- **문제**: Thread.sleep(1500) 후 창 개수 비교 → 느린 사이트에서 팝업이 1.5초 내 안 열리면 인페이지 분기로 잘못 빠짐
- **수정 방향**: 1.5초 고정 → 최대 5초 폴링(500ms 간격)으로 창 열림 감지
- **상태**: [ ] 미완료

### [P4] Gov24 - 동/호명칭 선택 결과 void 반환 → 실패해도 신청하기 진행
- **파일**: Gov24Automation.java
- **위치**: `fillBuildingForm` (약 1119, 1122줄) → `clickBuildingDongSearchAndSelect`, `clickHoSearchAndSelect` 호출
- **문제**: 두 메서드 void 반환 → 동/호 선택 실패해도 신청하기 버튼으로 이어짐 → 빈 폼 신청
- **수정 방향**: 메서드를 boolean 반환으로 변경, 실패 시 로그 출력 후 return false
- **상태**: [ ] 미완료

### [P5] IROS - 파일 다운로드 실패가 성공처럼 보임
- **파일**: IrosAutomation.java
- **위치**: `handlePostPaymentView` 메서드, 약 1009-1023줄
- **문제**: `waitForNewFile(30초)` null 반환 시 "저장 완료(파일 위치 확인)" 출력 → 사용자가 성공으로 오인
- **수정 방향**: null 반환 시 명확한 실패 메시지로 변경 ("PDF 다운로드 실패 - 30초 내 파일 생성 안됨")
- **상태**: [x] 완료 - "PDF 다운로드 실패 - 30초 내 파일 생성 안됨 (경로: ...)" 메시지로 변경

### [P6] IROS - 그리드 렌더링 감지 false positive (헤더 행 포함)
- **파일**: IrosAutomation.java
- **위치**: `executeSearchAndSelect`, 약 463-473줄
- **문제**: `tr 텍스트 > 5자`이면 준비 완료로 판단 → 헤더 행 있으면 결과 없어도 통과
- **수정 방향**: `td` 를 포함하는 tr만 체크하도록 변경 (`querySelector('td')` 체크 추가)
- **상태**: [x] 완료 - `trs[i].querySelector('td') &&` 조건 추가

### [P7] Gov24 - 로그인 실패 시 5분 블록
- **파일**: Gov24Automation.java
- **위치**: `doGov24Login`, 약 417줄
- **문제**: `waitForText(driver, 300000, "로그아웃")` — CAPTCHA 아닌 경우도 5분 대기
- **수정 방향**: 별도 처리 불필요 (이미 CAPTCHA 감지 로직 존재). 단 non-CAPTCHA 경우 타임아웃을 30초로 줄이거나 로그인 실패 빠른 감지 추가
- **상태**: [ ] 미완료

### [P8] IROS - "처리 중" 타임아웃 후 다음 단계 진행
- **파일**: IrosAutomation.java
- **위치**: `waitForTextGone` 호출 여러 곳
- **문제**: "처리 중" 사라지지 않아도 계속 진행
- **수정 방향**: waitForTextGone 후 실제로 사라졌는지 확인, 안 사라지면 경고 로그
- **상태**: [ ] 미완료

---

## 완료된 수정 (이전 작업)
- [완료] IROS selectResultByUnit 1차 재시도(3회, 1.5초) - executeSearchAndSelect 481줄
- [완료] IROS selectResultByUnit 2차 재시도(3회, 1.5초) - searchAndDownload 533줄
- [완료] IROS 동 번호 없이 호만 있는 주소 처리 (방이동 39 104호 케이스)
- commit: e4c6b10 (소재지번 재시도), 1c09af2 (1차 재시도), fb86a40 (동 없는 호 처리)
