package io.yuan.octopus.review.parsing;

import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.dto.ReviewRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequirementParserTest {

    private final RequirementParser parser = new RequirementParser();

    @Test
    void parseFromApiFields() {
        ReviewRequest req = new ReviewRequest();
        req.setRequirements(List.of(
                new RequirementItem("REQ-001", "参数校验", "对外接口必须使用 @Valid"),
                new RequirementItem("REQ-002", "异常处理", "使用 BusinessException")
        ));

        List<RequirementItem> items = parser.parse(req);

        assertEquals(2, items.size());
        assertEquals("REQ-001", items.get(0).getId());
        assertEquals("参数校验", items.get(0).getTitle());
    }

    @Test
    void parseFromMarkdownContent() {
        String md = """
                # XX 模块需求

                ## REQ-001: 参数校验
                对外接口必须使用 @Valid 校验请求参数。

                ## REQ-002: 异常处理
                业务异常使用 BusinessException。
                """;

        List<RequirementItem> items = parser.parseDocument(md, "inline-test");

        assertEquals(2, items.size());
        assertEquals("REQ-001", items.get(0).getId());
        assertEquals("参数校验", items.get(0).getTitle());
        assertTrue(items.get(0).getDescription().contains("@Valid"));
        assertEquals("REQ-002", items.get(1).getId());
    }

    @Test
    void parseMarkdownWithExtraWhitespace() {
        String md = """
                ##   REQ-001  :  参数校验
                描述
                """;

        List<RequirementItem> items = parser.parseDocument(md, "inline-test");

        assertEquals(1, items.size());
        assertEquals("REQ-001", items.get(0).getId());
    }

    @Test
    void fallbackToSingleDocumentRequirement() {
        String md = "# No requirements here";

        List<RequirementItem> items = parser.parseDocument(md, "inline-test");

        assertEquals(1, items.size());
        assertEquals("DOC-001", items.get(0).getId());
    }

    @Test
    void parseEmptyRequirementsWhenNoRequirementInput() {
        ReviewRequest req = new ReviewRequest();

        List<RequirementItem> items = parser.parse(req);

        assertTrue(items.isEmpty());
    }

    @Test
    void requirementsTakePriorityOverDocPath() {
        ReviewRequest req = new ReviewRequest();
        req.setRequirements(List.of(
                new RequirementItem("REQ-001", "API", "From API")
        ));
        req.setDocPath("/some/doc.md");

        List<RequirementItem> items = parser.parse(req);

        assertEquals(1, items.size());
        assertEquals("REQ-001", items.get(0).getId());
    }
}
