package com.winter.airesumeoptimizer.infra.render;

import java.util.Arrays;

/**
 * 内置只读简历模板。模板源码随应用打包，只负责展示，不保存任何业务数据；
 * 每个模板固定版本，升级模板必须新增版本而不是原位修改，保证 ExportArtifact 可解释。
 */
public enum ResumeTemplateId {

    CLASSIC("classic", "5", "typst/classic/v5/main.typ"),
    MODERN("modern", "3", "typst/modern/v3/main.typ"),
    MINIMAL("minimal", "3", "typst/minimal/v3/main.typ");

    private final String templateId;
    private final String templateVersion;
    private final String resourcePath;

    ResumeTemplateId(String templateId, String templateVersion, String resourcePath) {
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.resourcePath = resourcePath;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    /** 未知模板 fail closed，调用方负责把 null 映射为 400。 */
    public static ResumeTemplateId fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase();
        return Arrays.stream(values())
                .filter(template -> template.templateId.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
