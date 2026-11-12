package com.github.yingli.prompt;

/**
 * <p>MCP 工具 description 里重复出现的公共约束片段，集中维护。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>所有常量为 {@code public static final String}，可直接在 {@code @Tool(description = ...)}
 *       注解中引用（编译器会内联字符串，零运行时开销）。</li>
 *   <li>常量按"约束类型"分组，名称即含义，方便 grep 定位。</li>
 *   <li>改规则时只需改这一个文件，所有引用处自动生效。</li>
 * </ul>
 *
 * <p>2026-11-06 从 MetricTool / AdhocTool / CrmAnalyticsTool 的 @Tool description
 * 中抽离出 4 类共 15+ 处重复约束：caliber 输出禁令、空结果语义、errorCode 转述、不多给建议。</p>
 */
public final class ToolPromptSnippets {

    private ToolPromptSnippets() {
        // utility class — 禁止实例化
    }

    // ---------------------------------------------------------------------
    // 输出内容约束（所有查询工具共享）
    // ---------------------------------------------------------------------

    /** caliber 口径字段是服务端内部审计用的，禁止透传给最终用户。 */
    public static final String CALIBER_FORBIDDEN =
            "caliber 字段是内部审计用的，禁止输出给用户。";

    /**
     * 内部过滤条件、字段名、默认过滤器、记录数推导过程都属于服务端实现细节，
     * 不得以任何形式（包括改写、转述）写进回答；只给业务结论。
     */
    public static final String NO_INTERNAL_FILTER_EXPLANATION =
            "禁止在回答中输出或转述任何内部实现细节：字段名、过滤条件（如 track、del_flag 的取值）、"
          + "条件模板名、默认过滤器、记录数推导或排除过程；"
          + "回答只给业务结论（会员级别、归属门店、是否要客、数字等）。"
          + "仅当用户明确问“怎么算的/口径是什么”时才解释口径。";

    /**
     * 「会员级别」是复合口径（多列候选），用户未指明确切口径时必须让用户选择，不得默认。
     */
    public static final String AMBIGUOUS_MEMBER_LEVEL =
            "会员级别有多个口径：MEMBER_LEVEL（新会员等级 初享~鼎享）、HAIDING_LEVEL（CRM级别）、"
          + "COMPANY_LEVEL（公司级别/毛利级别，18 个月）、CITY_LEVEL（城市级别）、STORE_LEVEL（门店级别）、"
          + "PERSONAL_LEVEL（个人级别）、HALF_YEAR_SALES_LEVEL（一年半销售级别，18 个月）、"
          + "TOTAL_YEAR_SALES_LEVEL（总销售级别，全部时间）。三个销售/毛利级别的编码已按 CRM set_level 核实："
          + "COMPANY_LEVEL 21 个（AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FC、GA~GC）、"
          + "HALF_YEAR_SALES_LEVEL 17 个（AA~AC、BA~BC、CA~CC、DA~DC、EA~EC、FA~FB）、"
          + "TOTAL_YEAR_SALES_LEVEL 24 个（AA~AC 至 HA~HC）；set_level 无中文名称，"
          + "不得声称某个编码对应某个等级，按维度编码原样返回即可。用户没说清是哪套口径时，"
          + "必须列出候选让用户选，不得自行默认一个；用户选定后按对应维度查询。";

    /** 不要给用户额外建议、对比、趋势或归因。 */
    public static final String NO_SUGGESTION =
            "不要给出多余建议。";

    /** 回答直接给结论，不要开场白或解释性文字。 */
    public static final String STRAIGHT_ANSWER =
            "回答直接给数字和结论，不要加 好的、根据查询结果、以下是、统计口径是、数据来源于 "
          + "等开场白或解释性文字；用户不关心这些。";

    // ---------------------------------------------------------------------
    // 空结果 / 零值语义
    // ---------------------------------------------------------------------

    /** rows 为空或计数为 0 是合法答案，禁止换参数补查。 */
    public static final String EMPTY_RESULT_ZERO =
            "rows 为空或计数为 0 属正常结果：直接报告该区间 0 条，"
          + "禁止更换时间范围或参数补查其他区间。";

    // ---------------------------------------------------------------------
    // errorCode / 失败处理
    // ---------------------------------------------------------------------

    /** 参数非法、口径未确认等确定性失败，必须如实转述原因，不得编造数字或变换参数绕过。 */
    public static final String ERRORCODE_VERBOSE =
            "参数非法、口径未确认或执行失败时返回 errorCode+中文原因，"
          + "必须如实转述，不得编造数字或变换参数绕过。";

    /** adhocQuery 特有的超时提示（与固化指标工具略不同，单独一条）。 */
    public static final String ADHOC_TIMEOUT_HINT =
            "返回 ADHOC_QUERY_TIMEOUT 表示超过服务端 60 秒上限："
          + "只能缩小时间范围或增加等值过滤后重查一次，不得用相同参数重复查询。";

    // ---------------------------------------------------------------------
    // 完整手机号直查
    // ---------------------------------------------------------------------

    /**
     * 完整手机号可直接点查：等值过滤即精确命中，不必先解析会员；手机号列禁止任何模糊匹配。
     */
    public static final String PHONE_DIRECT_QUERY =
            "用户已给出完整手机号时，可直接用各实体的手机号列等值过滤：member→member_mobile、"
          + "order→expense_mobile、detail→expense_d_mobile；detail 的手机号等值即守卫定向条件，无需 timeRange，"
          + "member/order 也不强制时间范围。手机号列只支持精确匹配，禁止 LIKE/前缀/包含等模糊匹配；"
          + "号码不完整时向用户索取完整手机号，不要改用模糊查询。也不必先调 guessMemberByMobile 解析会员。";

    // ---------------------------------------------------------------------
    // 个人信息保护（各工具描述里散落的 PII 禁令，也统一收进常量）
    // ---------------------------------------------------------------------

    /** 聚合结果不应该有个人信息明细。 */
    public static final String NO_PII =
            "只返回汇总数据，不含任何个人信息。";

}
