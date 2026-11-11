package com.github.yingli.shared.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * @Description 派生指标计算器：客单价、人均、占比这类确定性计算由 Java 完成，不让模型靠语言推理算数。
 * <p>
 * 刻意<b>只</b>支持四则运算与括号，且操作数必须是同指标内已声明的度量/派生编码或数字常量。
 * 不使用 SpEL / Aviator 等表达式引擎：一是避免引入新依赖，二是表达式引擎本身就是注入面。
 * 除零与缺值统一返回 0，保持与 Phase 1 divide() 一致的语义。
 * @Author crm-agent
 */
public final class DerivedCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private DerivedCalculator() {
    }

    /**
     * 配置期校验公式合法性。
     *
     * @param formula   公式，如 "SALES / ORDERS"
     * @param operands  当前可引用的编码集合（度量 + 已定义的派生指标）
     * @return 合法返回 null；否则返回可读的中文原因
     */
    public static String checkFormula(String formula, Set<String> operands) {
        if (formula == null || formula.trim().isEmpty()) {
            return "公式为空";
        }
        try {
            Node node = new Parser(formula, operands).parseAll();
            if (!(node instanceof OpNodeImpl)) {
                return "公式必须至少包含一个四则运算符";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * 运行期求值。
     *
     * @param formula 公式
     * @param values  编码 -> 数值（缺失按 0 处理）
     * @param scale   结果保留小数位
     */
    public static BigDecimal evaluate(String formula, Map<String, BigDecimal> values, int scale) {
        Node node = new Parser(formula, values.keySet()).parseAll();
        BigDecimal r = node.eval(values);
        return r.setScale(scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // AST
    // ------------------------------------------------------------------

    private interface Node {
        BigDecimal eval(Map<String, BigDecimal> values);
    }

    /** 叶子：编码引用或数字常量 */
    private record LeafNode(String token, boolean constant) implements Node {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> values) {
            if (constant) {
                return new BigDecimal(token, MC);
            }
            BigDecimal v = values.get(token);
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    /** 二元四则运算节点；除零返回 0，与 Phase 1 divide() 语义一致 */
    private record OpNodeImpl(char op, Node left, Node right) implements Node {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> values) {
            BigDecimal a = left.eval(values);
            BigDecimal b = right.eval(values);
            return switch (op) {
                case '+' -> a.add(b, MC);
                case '-' -> a.subtract(b, MC);
                case '*' -> a.multiply(b, MC);
                case '/' -> b.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : a.divide(b, MC);
                default -> throw new IllegalStateException("不支持的运算符: " + op);
            };
        }
    }

    // ------------------------------------------------------------------
    // 递归下降解析：expr = term (('+'|'-') term)* ; term = factor (('*'|'/') factor)*
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String src;
        private final Set<String> operands;
        private int pos;

        Parser(String src, Set<String> operands) {
            this.src = src;
            this.operands = operands;
        }

        Node parseAll() {
            Node n = parseExpr();
            skipWs();
            if (pos < src.length()) {
                throw new IllegalArgumentException("公式在位置 " + pos + " 有多余内容：" + src);
            }
            return n;
        }

        private Node parseExpr() {
            Node left = parseTerm();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                    left = new OpNodeImpl(c, left, parseTerm());
                } else {
                    return left;
                }
            }
        }

        private Node parseTerm() {
            Node left = parseFactor();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*' || c == '/') {
                    pos++;
                    left = new OpNodeImpl(c, left, parseFactor());
                } else {
                    return left;
                }
            }
        }

        private Node parseFactor() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos++;
                Node n = parseExpr();
                skipWs();
                if (peek() != ')') {
                    throw new IllegalArgumentException("公式括号不匹配：" + src);
                }
                pos++;
                return n;
            }
            if (c == '-') {
                pos++;
                Node n = parseFactor();
                return new OpNodeImpl('*', new LeafNode("-1", true), n);
            }
            return parseAtom();
        }

        private Node parseAtom() {
            skipWs();
            int start = pos;
            char c = peek();
            if (Character.isDigit(c) || c == '.') {
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                String num = src.substring(start, pos);
                try {
                    new BigDecimal(num);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("公式中的数字常量非法：" + num);
                }
                return new LeafNode(num, true);
            }
            if (Character.isLetter(c) || c == '_') {
                while (pos < src.length()
                        && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    pos++;
                }
                String id = src.substring(start, pos);
                // 运行期 keySet 在含派生编码时可能尚未包含自身引用，交由调用方保证顺序
                if (!operands.contains(id)) {
                    throw new IllegalArgumentException("公式引用了未声明的编码：" + id + "，公式=" + src);
                }
                return new LeafNode(id, false);
            }
            throw new IllegalArgumentException("公式中出现无法识别的字符（位置 " + pos + "）：" + src);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }
    }
}
