package com.pankscan.app;

/**
 * 一处集中配置：自动更新从哪里取。
 *
 * 只改这一行就能换仓库——`publish.sh` 也用 grep 从本文件读 OWNER/REPO，
 * 所以脚本和 App 不会各写一份地址而对不上。
 */
final class AppConfig {

    /** GitHub 用户名 / 组织名 */
    static final String OWNER = "yuguod";

    /** 仓库名 */
    static final String REPO = "pankscan";

    /** 分支名，update.json 放在这个分支的根目录 */
    static final String BRANCH = "main";

    /**
     * 版本清单地址。用 raw.githubusercontent.com 而不是 Releases API，
     * 是因为它不消耗 API 配额、不需要令牌，也不会被匿名请求限速。
     */
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/" + BRANCH + "/update.json";

    private AppConfig() {
    }
}
