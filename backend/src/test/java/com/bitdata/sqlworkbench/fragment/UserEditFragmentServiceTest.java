package com.bitdata.sqlworkbench.fragment;

import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserEditFragmentServiceTest {

    private final UserEditFragmentService service = new UserEditFragmentService();

    @Test
    void edit_newSql_createsNodeWithCorrectFields() {
        var result = service.createUserEdit(
            "SELECT * FROM orders WHERE status = 'active'",
            "parent-abc",
            Set.of("some-other-id"),
            "postgresql",
            null
        );

        assertEquals("EDIT", result.classification());
        assertFalse(result.navigatedToExisting());

        FragmentNode node = result.node();
        assertNotNull(node);
        assertEquals("USER_EDITED", node.type().name());
        assertEquals("SELECT * FROM orders WHERE status = 'active'", node.sqlFragment());
        assertEquals("parent-abc", node.editParentId());
        assertTrue(node.parentIds().isEmpty(), "user edits have no decomposition parentIds");
        assertNotNull(node.canonicalSql(), "canonicalSql must be populated");
        assertNotNull(node.createdAt(), "createdAt must be populated");
        assertEquals("USER", node.source().name());
        // ID is SHA-256 hex
        assertTrue(node.id().matches("[a-f0-9]{64}"), "id should be 64-char hex");
    }

    @Test
    void navigate_equivalentSql_returnsExistingId() {
        // First create a node to get its canonical identity
        var first = service.createUserEdit(
            "SELECT * FROM t WHERE a > 0",
            "parent-1",
            Set.of(),
            "postgresql",
            null
        );
        String existingId = first.node().id();

        // Now submit equivalent SQL (different whitespace/case) with existing ID in DAG
        var second = service.createUserEdit(
            "select * from t where a > 0",
            "parent-2",
            Set.of(existingId),
            "postgresql",
            null
        );

        assertEquals("NAVIGATE", second.classification());
        assertTrue(second.navigatedToExisting());
        assertEquals(existingId, second.node().id());
    }

    @Test
    void edit_differentSql_createsNewId() {
        var first = service.createUserEdit("SELECT 1", "parent-1", Set.of(), "postgresql", null);
        var second = service.createUserEdit("SELECT 2", "parent-1", Set.of(first.node().id()), "postgresql", null);

        assertEquals("EDIT", second.classification());
        assertNotEquals(first.node().id(), second.node().id());
    }

    @Test
    void edit_label_usesDescriptionWhenProvided() {
        var result = service.createUserEdit(
            "SELECT 1", "parent-1", Set.of(), "postgresql", null
        );
        // Default label when no description provided
        assertNotNull(result.node().label());
        assertFalse(result.node().label().isBlank());
    }

    @Test
    void edit_label_usesNonBlankDescription() {
        var result = service.createUserEdit(
            "SELECT * FROM orders WHERE status = 'active'",
            "parent-1",
            Set.of(),
            "postgresql",
            "removed WHERE clause to test"
        );
        assertEquals("EDIT", result.classification());
        assertEquals("removed WHERE clause to test", result.node().label());
    }

    @Test
    void edit_label_blankDescription_fallsBackToAutoLabel() {
        var result = service.createUserEdit(
            "SELECT 1", "parent-1", Set.of(), "postgresql", "   "
        );
        assertEquals("EDIT", result.classification());
        assertTrue(result.node().label().startsWith("Edit: "));
    }

    @Test
    void unsupportedShapeReturnsUnsupportedWithReasonInsteadOfAnIdentity() {
        // spec §4.2 / D3：规范化命中兜底时不得再走 NAVIGATE/EDIT 判定。
        // 旧实现实测：本输入 → classification=EDIT, nodePresent=true（即「照常给身份」）。
        // 该身份当时还**跨语句**碰撞；T1 的内容摘要已消除跨语句碰撞（本输入
        // `SqlUpdate#607b24d415a1`，`UPDATE u SET b = 2` 为 `SqlUpdate#0b31fa0fd340`）。
        // 本任务关闭的是剩下的一半 —— 降级形态根本不该被算出身份。
        var result = service.createUserEdit(
            "UPDATE t SET a = 1", "parent-1", Set.of(), "postgresql", null);

        assertEquals("UNSUPPORTED", result.classification());
        assertNotNull(result.errorReason(), "降级必须可观测（规则 #2）");
        assertFalse(result.errorReason().isBlank());
        assertTrue(result.errorReason().contains("SqlUpdate"),
            "原因应点名未分派的类型，实际=" + result.errorReason());
        assertFalse(result.navigatedToExisting(), "降级不是一次导航");
        assertNull(result.node(), "降级不创建节点");
    }

    @Test
    void unsupportedShapeIsNotNavigatedEvenWhenTheDagHoldsTheIdentityItWouldHaveHad() throws Exception {
        // 票 02 的根症状（编辑回到自己）：降级形态**曾经**会被算出身份；一旦该身份已在 DAG 中，
        // 旧实现就判成 NAVIGATE 到自身。这里把「它本该算出的那个 id」显式塞进 DAG 来对质
        // —— 新实现必须在算身份**之前**拒绝，故即便 id 命中也不得导航、不得建节点。
        //
        // 旧实现实测（task-4-report.md §4 探针，d924ba9 源码）：
        //   同一条 SQL + 自己的 id → classification=NAVIGATE, navigatedToExisting=true, sameId=true
        // ⇒ 下面三条断言在旧实现上均红。新实现 → UNSUPPORTED。
        String sql = "UPDATE t SET a = 1";
        String degradedCanonical =
            SqlCanonicalizer.canonicalizeSqlWithStatus(sql, "postgresql").canonicalSql();
        String degradedId = FragmentIdentity.computeId(degradedCanonical, "postgresql");

        var result = service.createUserEdit(sql, "parent-1", Set.of(degradedId), "postgresql", null);

        assertEquals("UNSUPPORTED", result.classification());
        assertFalse(result.navigatedToExisting(), "降级不是一次导航，哪怕算出来的 id 命中 DAG");
        assertNull(result.node(), "降级不创建节点");
    }

    @Test
    void supportedSqlStillClassifiesAsBefore() {
        // 反向约束：本任务不得把正常路径一起打成 UNSUPPORTED。
        var result = service.createUserEdit(
            "SELECT * FROM orders WHERE status = 'active'", "parent-1", Set.of(), "postgresql", null);
        assertEquals("EDIT", result.classification());
        assertNull(result.errorReason(), "非降级时 errorReason 必须为 null");
        assertNotNull(result.node());
    }

    @Test
    void unparseableSqlIsUnsupportedNotRawIdentity() {
        // 第二条降级源（spec §4.2 表第二行）：解析失败此前拿 raw SQL 算身份（log-only）。
        var result = service.createUserEdit(
            "SELECT FROM WHERE ((", "parent-1", Set.of(), "postgresql", null);
        assertEquals("UNSUPPORTED", result.classification());
        assertNotNull(result.errorReason());
        assertNull(result.node());
    }

    @Test
    void blankSqlIsUnsupportedNotAnIdentity() {
        // 报告 §5：brief Step-3 新增的拒绝分支。旧实现实测该输入 → EDIT（带节点）。
        var result = service.createUserEdit("   ", "parent-1", Set.of(), "postgresql", null);
        assertEquals("UNSUPPORTED", result.classification());
        assertNotNull(result.errorReason(), "降级必须可观测（规则 #2）");
        assertFalse(result.errorReason().isBlank());
        assertNull(result.node());
    }
}
