# @JavascriptInterface 를 쓰지 않으므로(WebMessagePort 만 사용) 별도 keep 규칙이 필요 없다.
# 릴리스 빌드에서 android.util.Log 호출을 제거한다 (PRD SEC-6)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
