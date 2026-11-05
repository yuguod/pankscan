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
     * 版本清单：raw.githubusercontent.com。
     * 不消耗 API 配额、不需要令牌、不会被匿名请求限速；但国内直连常常解析不了。
     */
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/" + BRANCH + "/update.json";

    /**
     * 版本清单：jsDelivr 的 GitHub 镜像。
     * 直连能通，但**分支引用的缓存最长 12 小时**，加时间戳参数也绕不过去，
     * 所以只当兜底，不能当主源。
     */
    static final String MANIFEST_URL_CDN =
            "https://cdn.jsdelivr.net/gh/" + OWNER + "/" + REPO + "@" + BRANCH + "/update.json";

    /**
     * 版本清单：GitHub 官方 API（contents 接口）。
     *
     * 实测本地网络下这个域名直连 200，且**没有 CDN 那层缓存**，发完版立刻读得到新清单，
     * 所以放第一位。请求时带 Accept: application/vnd.github.raw，直接拿到文件原文；
     * 未登录限速 60 次/小时/IP，检查更新这个频率远远够用。
     */
    static final String MANIFEST_URL_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/contents/update.json?ref=" + BRANCH;

    /** 按顺序尝试，第一个通的就用 */
    static final String[] MANIFEST_SOURCES = {MANIFEST_URL_API, MANIFEST_URL, MANIFEST_URL_CDN};

    private AppConfig() {
    }
}
