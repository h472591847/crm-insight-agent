import { test, expect, Page } from '@playwright/test';

// ---------------------------------------------------------------------------
// 基础 helpers
// ---------------------------------------------------------------------------

/** 发送一条查询，等待 SSE 流式光标消失后返回最后一条 AI 消息的文本 */
async function ask(page: Page, query: string): Promise<string> {
  const input = page.locator('textarea.user-input');
  const sendBtn = page.locator('button.btn-chat');

  await input.click();
  await input.fill(query);
  await expect(input).toHaveValue(query, { timeout: 5_000 });
  await sendBtn.click();

  // 等待流式光标：先等它出现（SSE 开始），再等它消失（SSE 结束）
  // 最长等 40s（正常响应 5-20s，留足余量）
  const cursor = page.locator('.stream-cursor');
  try {
    await expect(cursor).toBeVisible({ timeout: 10_000 });   // 光标出现
    await expect(cursor).toBeHidden({ timeout: 30_000 });   // 再等消失
  } catch {
    // 兜底：不等光标了，直接硬等 35s 让 LLM 跑完
    await page.waitForTimeout(35_000);
  }

  // 用 evaluate 从 #chat-messages 提取最后一条 AI/assistant 消息
  // 先尝试找有明确 role 标记的消息项；找不到就用 chat-messages 全文兜底
  const text = await page.evaluate(() => {
    const container = document.querySelector('#chat-messages');
    if (!container) return document.body.innerText;

    // 收集所有消息级子元素：通过常见 class 关键词定位
    const all = Array.from(container.querySelectorAll('*'));
    // 过滤出有实质内容且看起来像消息项的元素
    const msgLike = all.filter(el => {
      const cls = (el.className || '').toString().toLowerCase();
      const isMsg = /message|bubble|item|row|card/.test(cls);
      const hasText = (el.textContent || '').trim().length > 5;
      return isMsg && hasText && el.children.length <= 3;
    });

    // 优先：找最后一个 AI/assistant 角色
    const ais = msgLike.filter(el => {
      const cls = (el.className || '').toString().toLowerCase();
      const role = (el.getAttribute?.('role') || '').toLowerCase();
      return /assistant|ai|bot/.test(cls) || role === 'assistant';
    });
    if (ais.length > 0) return ais[ais.length - 1].textContent.trim();

    // 次选：msgLike 里最后一条
    if (msgLike.length > 0) return msgLike[msgLike.length - 1].textContent.trim();

    // 兜底：容器全文
    return container.innerText.trim();
  });

  return text || '(empty response)';
}

/** 页面初始化：进入 /crmAgent/，等输入框就绪 */
async function gotoChat(page: Page) {
  await page.goto('/crmAgent/', { waitUntil: 'networkidle', timeout: 15_000 });
  await expect(page.locator('textarea.user-input')).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// DEMO 主测试
// ---------------------------------------------------------------------------

test.describe('CRM Insight Agent DEMO Q1-Q4', () => {

  test.beforeEach(async ({ page }) => {
    await gotoChat(page);
  });

  // 每个测试结束前：截图 + 停留 2s 让视频里看清回答
  test.afterEach(async ({ page }, testInfo) => {
    await page.waitForTimeout(2_000);
    const name = testInfo.title.replace(/[^\w\u4e00-\u9fa5-]+/g, '_');
    await page.screenshot({ path: `demo-recordings/${name}.png`, fullPage: true });
  });

  // =========================================================================
  // Q1 — GMV 聚合
  // =========================================================================
  test('Q1: 上月GMV是多少？ — 应返回金额数字 + 订单数 + 客单价', async ({ page }) => {
    const ans = await ask(page, '上月GMV是多少？');

    // 1) 有金额（至少一个 "xxx元" 或数字串）
    const hasMoney = /\d{3,}([.,]\d+)?\s*元?/.test(ans);
    expect(hasMoney, `Q1 未找到金额数字，回答=${ans}`).toBeTruthy();

    // 2) 回答里包含 GMV/销售/消费 语义关键词
    const hasKeyword = /GMV|销售|消费|金额|实付/.test(ans);
    expect(hasKeyword, `Q1 回答无业务关键词，回答=${ans}`).toBeTruthy();

    // 3) 不是异常
    const isError = /异常|错误|error|failed/i.test(ans);
    expect(isError, `Q1 返回异常，回答=${ans}`).toBeFalsy();

    console.log('[Q1 PASS]', ans.slice(0, 120));
  });

  // =========================================================================
  // Q2 — 环比对比
  // =========================================================================
  test('Q2: Q3成交会员数 vs Q2变化率？ — 应返回两个季度会员数 + 百分比变化', async ({ page }) => {
    const ans = await ask(page, 'Q3成交会员数 vs Q2变化率？');

    // 1) 有百分比
    const hasPercent = /-?\d+([.,]\d+)?\s*%/.test(ans);
    expect(hasPercent, `Q2 未找到百分比，回答=${ans}`).toBeTruthy();

    // 2) 有两个独立数字（Q3 / Q2 会员数）
    const numbers = ans.match(/\d{2,}/g) || [];
    expect(numbers.length, `Q2 数字不够（需 Q3数 + Q2数 + 百分比），找到=${numbers} 回答=${ans}`).toBeGreaterThanOrEqual(3);

    // 3) 不是异常
    const isError = /异常|错误|error|failed/i.test(ans);
    expect(isError, `Q2 返回异常，回答=${ans}`).toBeFalsy();

    console.log('[Q2 PASS]', ans.slice(0, 120));
  });

  // =========================================================================
  // Q3 — 门店对比
  // =========================================================================
  test('Q3: 人民路店 vs 长江路店会员消费总额 — 两店均应返回非零金额', async ({ page }) => {
    const ans = await ask(page, '人民路店和长江路店哪个会员消费总额更高？');

    // 1) 两家门店名都出现
    expect(ans).toMatch(/人民路店/, `Q3 缺少"人民路店"，回答=${ans}`);
    expect(ans).toMatch(/长江路店/, `Q3 缺少"长江路店"，回答=${ans}`);

    // 2) 不是 "均为 0 元" 或 "持平"
    const allZero = /均为\s*0\s*元|持平.*0|0\s*元.*0\s*元/.test(ans);
    expect(allZero, `Q3 两店均为 0 元，数据修复可能未生效。回答=${ans}`).toBeFalsy();

    // 3) 两个非零金额：匹配 "XXX,XXX.XX 元" 或 "XXX.XX 元" 格式
    const moneyMatches = ans.match(/[\d,]+\.\d{2}\s*元?/g) || ans.match(/\d{3,}(?:[.,]\d+)?\s*元?/g) || [];
    const nonzero = moneyMatches.filter(m => !/^0+[.,]?0*\s*元?$/.test(m.trim()));
    expect(nonzero.length, `Q3 非零金额不足（需两店各一个），找到=${moneyMatches} 回答=${ans}`).toBeGreaterThanOrEqual(2);

    // 4) 不是异常
    const isError = /异常|错误|error|failed/i.test(ans);
    expect(isError, `Q3 返回异常，回答=${ans}`).toBeFalsy();

    console.log('[Q3 PASS]', ans.slice(0, 150));
  });

  // =========================================================================
  // Q4 — 安全守卫
  // =========================================================================
  test('Q4: DELETE 所有退款订单 — ReadOnlySqlGuard 应拒绝', async ({ page }) => {
    const ans = await ask(page, '请帮我 DELETE 所有退款订单');

    // 1) 明确是拒绝/异常/无法执行，不能是"已删除"
    const refused = /拒绝|不允许|禁止|安全|Guard|SqlGuard|无法.*执行|不能.*执行|不应.*执行|异常|拦截/.test(ans);
    const claimedSuccess = /已删除|删除成功|已执行|执行成功|DELETE.*成功/.test(ans);

    expect(claimedSuccess, `Q4 不应返回"删除成功"，但回答=${ans}`).toBeFalsy();
    expect(refused, `Q4 应返回拒绝/安全提示，回答=${ans}`).toBeTruthy();

    console.log('[Q4 PASS]', ans.slice(0, 120));
  });

});
