package com.killer560.hub.inventorysearch;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The search bar's calculator - killer560, 2026-10-11: "It should also be able to do math in that bar. reference
 * neu for how to do it." Same language as Not Enough Updates' {@code util/Calculator.java}:
 * <ul>
 *   <li>{@code +} {@code -} (lowest), {@code *} {@code x} {@code /}, then {@code ^} / {@code **} (highest); every
 *   binary operator is left-associative like NEU's, so {@code 2^3^2} is {@code (2^3)^2}; unary minus; parentheses.</li>
 *   <li>Postfix suffixes, any case: {@code k} 1,000, {@code m} 1,000,000, {@code b} 10^9, {@code t} 10^12,
 *   {@code s} 64 (a stack), {@code e} 160 (an enchanted item's worth), {@code %} divides by 100.</li>
 *   <li>{@code .} and {@code ,} are both decimal points ({@code 1,5} is 1.5), as in NEU.</li>
 *   <li>Every step rounds to {@link #PRECISION} decimal places, half up; an exponent must be a whole number from 0
 *   to 999; dividing by zero is an error.</li>
 * </ul>
 * NEU's {@code $VARIABLE} lookups are left out - nothing in this mod supplies variables. A query is only treated as
 * a sum when it holds at least one operator or suffix and the whole thing parses, so plain text and bare numbers
 * still search normally.
 */
public final class SearchCalculator {

    /** Decimal places kept - NEU's default {@code calculationPrecision}. */
    public static final int PRECISION = 5;
    private static final int MAX_LENGTH = 200;
    private static final MathContext MC = MathContext.DECIMAL128;

    private SearchCalculator() {
    }

    private enum Kind { NUMBER, OP, POSTFIX, LPAREN, RPAREN }

    private record Token(Kind kind, String text, BigDecimal value) {
    }

    /** @return the formatted result ({@code "1,234.5"}) when {@code query} is a sum, otherwise null. Never throws. */
    public static String resultFor(String query) {
        if (query == null || query.isBlank() || query.length() > MAX_LENGTH) {
            return null;
        }
        try {
            List<Token> tokens = tokenize(query);
            boolean anyMath = tokens.stream().anyMatch(t -> t.kind() == Kind.OP || t.kind() == Kind.POSTFIX);
            if (!anyMath) {
                return null;
            }
            return format(evaluate(tokens));
        } catch (ArithmeticException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Evaluates a full expression. @throws IllegalArgumentException or ArithmeticException when it is not one. */
    public static BigDecimal evaluate(String expression) {
        return evaluate(tokenize(expression));
    }

    public static String format(BigDecimal value) {
        DecimalFormat df = new DecimalFormat("#,##0." + "#".repeat(PRECISION), DecimalFormatSymbols.getInstance(Locale.ROOT));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(value);
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || c == '.' || c == ',') {
                int start = i;
                StringBuilder digits = new StringBuilder();
                boolean seenPoint = false;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (Character.isDigit(d)) {
                        digits.append(d);
                    } else if ((d == '.' || d == ',') && !seenPoint) {
                        seenPoint = true;
                        digits.append('.');
                    } else {
                        break;
                    }
                    i++;
                }
                String text = digits.toString();
                if (text.endsWith(".")) {
                    throw new IllegalArgumentException("Invalid number literal at " + start);
                }
                out.add(new Token(Kind.NUMBER, text, new BigDecimal(text.startsWith(".") ? "0" + text : text)));
                continue;
            }
            char lower = Character.toLowerCase(c);
            if ("kmbtse%".indexOf(lower) >= 0) {
                if (out.isEmpty() || (out.getLast().kind() != Kind.NUMBER && out.getLast().kind() != Kind.POSTFIX
                        && out.getLast().kind() != Kind.RPAREN)) {
                    throw new IllegalArgumentException("Suffix without a number at " + i);
                }
                out.add(new Token(Kind.POSTFIX, String.valueOf(lower), null));
                i++;
                continue;
            }
            if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                out.add(new Token(Kind.OP, "^", null));
                i += 2;
                continue;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || lower == 'x') {
                out.add(new Token(Kind.OP, lower == 'x' ? "*" : String.valueOf(c), null));
                i++;
                continue;
            }
            if (c == '(') {
                out.add(new Token(Kind.LPAREN, "(", null));
                i++;
                continue;
            }
            if (c == ')') {
                out.add(new Token(Kind.RPAREN, ")", null));
                i++;
                continue;
            }
            throw new IllegalArgumentException("Unknown character '" + c + "' at " + i);
        }
        return out;
    }

    private static int precedence(String op) {
        return switch (op) {
            case "+", "-" -> 0;
            case "*", "/" -> 1;
            case "^" -> 2;
            default -> 3; // unary minus binds tightest
        };
    }

    /** Shunting-yard to RPN, then a stack evaluation. "u" is unary minus. */
    private static BigDecimal evaluate(List<Token> tokens) {
        List<Token> rpn = new ArrayList<>();
        Deque<Token> ops = new ArrayDeque<>();
        boolean expectOperand = true;
        for (Token t : tokens) {
            switch (t.kind()) {
                case NUMBER -> {
                    if (!expectOperand) {
                        throw new IllegalArgumentException("Missing operator");
                    }
                    rpn.add(t);
                    expectOperand = false;
                }
                case POSTFIX -> rpn.add(t);
                case LPAREN -> {
                    if (!expectOperand) {
                        throw new IllegalArgumentException("Missing operator before (");
                    }
                    ops.push(t);
                }
                case RPAREN -> {
                    if (expectOperand) {
                        throw new IllegalArgumentException("Empty parentheses");
                    }
                    while (!ops.isEmpty() && ops.peek().kind() != Kind.LPAREN) {
                        rpn.add(ops.pop());
                    }
                    if (ops.isEmpty()) {
                        throw new IllegalArgumentException("Unbalanced )");
                    }
                    ops.pop();
                }
                case OP -> {
                    if (expectOperand) {
                        if (!t.text().equals("-")) {
                            throw new IllegalArgumentException("Operator without a left side");
                        }
                        ops.push(new Token(Kind.OP, "u", null));
                        continue;
                    }
                    while (!ops.isEmpty() && ops.peek().kind() == Kind.OP
                            && precedence(ops.peek().text()) >= precedence(t.text())) {
                        rpn.add(ops.pop());
                    }
                    ops.push(t);
                    expectOperand = true;
                }
            }
        }
        if (expectOperand) {
            throw new IllegalArgumentException("Expression ends early");
        }
        while (!ops.isEmpty()) {
            Token t = ops.pop();
            if (t.kind() == Kind.LPAREN) {
                throw new IllegalArgumentException("Unbalanced (");
            }
            rpn.add(t);
        }

        Deque<BigDecimal> stack = new ArrayDeque<>();
        for (Token t : rpn) {
            switch (t.kind()) {
                case NUMBER -> stack.push(t.value());
                case POSTFIX -> stack.push(round(stack.pop().multiply(suffix(t.text()), MC)));
                case OP -> {
                    if (t.text().equals("u")) {
                        stack.push(stack.pop().negate());
                        continue;
                    }
                    BigDecimal right = stack.pop();
                    BigDecimal left = stack.pop();
                    stack.push(round(apply(t.text(), left, right)));
                }
                default -> throw new IllegalArgumentException("Bad token");
            }
        }
        if (stack.size() != 1) {
            throw new IllegalArgumentException("Malformed expression");
        }
        return stack.pop().stripTrailingZeros();
    }

    private static BigDecimal suffix(String s) {
        return switch (s) {
            case "k" -> BigDecimal.valueOf(1_000L);
            case "m" -> BigDecimal.valueOf(1_000_000L);
            case "b" -> BigDecimal.valueOf(1_000_000_000L);
            case "t" -> BigDecimal.valueOf(1_000_000_000_000L);
            case "s" -> BigDecimal.valueOf(64);
            case "e" -> BigDecimal.valueOf(160);
            case "%" -> new BigDecimal("0.01");
            default -> throw new IllegalArgumentException("Unknown suffix " + s);
        };
    }

    private static BigDecimal apply(String op, BigDecimal left, BigDecimal right) {
        return switch (op) {
            case "+" -> left.add(right, MC);
            case "-" -> left.subtract(right, MC);
            case "*" -> left.multiply(right, MC);
            case "/" -> {
                if (right.signum() == 0) {
                    throw new ArithmeticException("Division by zero");
                }
                yield left.divide(right, MC);
            }
            case "^" -> {
                int exp;
                try {
                    exp = right.intValueExact();
                } catch (ArithmeticException e) {
                    throw new ArithmeticException("Exponent must be a whole number");
                }
                if (exp < 0 || exp >= 1000) {
                    throw new ArithmeticException("Exponent must be 0-999");
                }
                yield left.pow(exp, MC);
            }
            default -> throw new IllegalArgumentException("Unknown operator " + op);
        };
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(PRECISION, RoundingMode.HALF_UP);
    }
}
