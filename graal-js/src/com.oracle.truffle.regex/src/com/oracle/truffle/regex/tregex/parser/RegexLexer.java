/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.parser;

import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexOptions;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.util.CompilationFinalBitSet;
import com.oracle.truffle.regex.util.Constants;
import java.math.BigInteger;

import java.util.EnumSet;
import java.util.regex.Pattern;

public final class RegexLexer {

    private static final CompilationFinalBitSet PREDEFINED_CHAR_CLASSES = CompilationFinalBitSet.valueOf('s', 'S', 'd', 'D', 'w', 'W');
    private static final CompilationFinalBitSet SYNTAX_CHARS = CompilationFinalBitSet.valueOf(
                    '^', '$', '/', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|');

    private final String pattern;
    private final RegexFlags flags;
    private final RegexOptions options;
    private Token lastToken;
    private int index = 0;
    private int nGroups = 1;
    private boolean countedAllGroups = false;

    public RegexLexer(RegexSource source, RegexOptions options) {
        this.pattern = source.getPattern();
        this.flags = source.getFlags();
        this.options = options;
    }

    public boolean hasNext() {
        return !atEnd();
    }

    public Token next() throws RegexSyntaxException {
        Token t = getNext();
        lastToken = t;
        return t;
    }

    /* input string access */

    private char curChar() {
        return pattern.charAt(index);
    }

    private char consumeChar() {
        final char c = pattern.charAt(index);
        advance();
        return c;
    }

    private void advance() {
        advance(1);
    }

    private void retreat() {
        advance(-1);
    }

    private void advance(int len) {
        index += len;
    }

    private boolean lookahead(String match) {
        if (pattern.length() - index < match.length()) {
            return false;
        }
        return pattern.regionMatches(index, match, 0, match.length());
    }

    private boolean consumingLookahead(String match) {
        final boolean matches = lookahead(match);
        if (matches) {
            advance(match.length());
        }
        return matches;
    }

    private boolean atEnd() {
        return index >= pattern.length();
    }

    private int numberOfCaptureGroups() {
        if (!countedAllGroups) {
            countCaptureGroups();
            countedAllGroups = true;
        }
        return nGroups;
    }

    private void registerCaptureGroup() {
        if (!countedAllGroups) {
            nGroups++;
        }
    }

    private void countCaptureGroups() {
        // We are counting capture groups, so we only care about '(' characters and special
        // characters which can cancel the meaning of '(' - those include '\' for escapes, '[' for
        // character classes (where '(' stands for a literal '(') and any characters after the '('
        // which might turn into a non-capturing group or a look-around assertion.
        boolean insideCharClass = false;
        Pattern nonCapturingGroup = Pattern.compile("\\((?:\\?[:=!]|\\<[=!])");
        int i = index;
        while (i < pattern.length()) {
            switch (pattern.charAt(i)) {
                case '\\':
                    // skip escaped char
                    i++;
                    break;
                case '[':
                    insideCharClass = true;
                    break;
                case ']':
                    insideCharClass = false;
                    break;
                case '(':
                    if (!insideCharClass && !nonCapturingGroup.matcher(pattern.substring(i)).matches()) {
                        nGroups++;
                    }
                    break;
                default:
                    break;
            }
            // advance
            i++;
        }
    }

    private Token charClass(CodePointSet codePointSet) {
        return charClass(codePointSet, false);
    }

    private Token charClass(CodePointSet codePointSet, boolean invert) {
        CodePointSet processedSet = codePointSet;
        processedSet = flags.isIgnoreCase() ? CaseFoldTable.applyCaseFold(processedSet, flags.isUnicode()) : processedSet;
        processedSet = invert ? processedSet.createInverse() : processedSet;
        return Token.createCharClass(processedSet);
    }

    /* lexer */

    private Token getNext() throws RegexSyntaxException {
        final char c = consumeChar();
        switch (c) {
            case '.':
                return charClass(flags.isDotAll() ? Constants.DOT_ALL : Constants.DOT);
            case '^':
                return Token.create(Token.Kind.caret);
            case '$':
                return Token.create(Token.Kind.dollar);
            case '{':
            case '*':
            case '+':
            case '?':
                return parseQuantifier(c);
            case '}':
                if (flags.isUnicode()) {
                    // In ECMAScript regular expressions, syntax characters such as '}' and ']'
                    // cannot be used as atomic patterns. However, Annex B relaxes this condition
                    // and allows the use of unmatched '}' and ']', which then match themselves.
                    // Neverthelesss, in Unicode mode, we should still be strict.
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACE);
                }
                return charClass(CodePointSet.create(c));
            case '|':
                return Token.create(Token.Kind.alternation);
            case '(':
                return parseGroupBegin();
            case ')':
                return Token.create(Token.Kind.groupEnd);
            case '[':
                return parseCharClass();
            case ']':
                if (flags.isUnicode()) {
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACKET);
                }
                return charClass(CodePointSet.create(c));
            case '\\':
                return parseEscape();
            default:
                if (flags.isUnicode() && Character.isHighSurrogate(c)) {
                    return charClass(CodePointSet.create(finishSurrogatePair(c)));
                }
                return charClass(CodePointSet.create(c));
        }
    }

    private Token parseEscape() throws RegexSyntaxException {
        if (atEnd()) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
        }
        final char c = consumeChar();
        if ('1' <= c && c <= '9') {
            final int restoreIndex = index;
            final int backRefNumber = parseInteger(c - '0');
            if (backRefNumber < numberOfCaptureGroups()) {
                return Token.createBackReference(backRefNumber);
            } else if (flags.isUnicode()) {
                throw syntaxError("missing capture group for back-reference");
            }
            index = restoreIndex;
        }
        switch (c) {
            case 'b':
                return Token.create(Token.Kind.wordBoundary);
            case 'B':
                return Token.create(Token.Kind.nonWordBoundary);
            default:
                // Here we differentiate the case when parsing one of the six basic pre-defined
                // character classes (\w, \W, \d, \D, \s, \S) and Unicode character property
                // escapes. Both result in sets of characters, but in the former case, we can skip
                // the case-folding step in the `charClass` method and call `Token::createCharClass`
                // directly.
                if (isPredefCharClass(c)) {
                    return Token.createCharClass(parsePredefCharClass(c));
                } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
                    return charClass(parseUnicodeCharacterProperty(c == 'P'));
                } else {
                    return charClass(CodePointSet.create(parseEscapeChar(c, false)));
                }
        }
    }

    private Token parseGroupBegin() {
        if (consumingLookahead("?=")) {
            return Token.create(Token.Kind.lookAheadAssertionBegin);
        } else if (consumingLookahead("?!")) {
            return Token.create(Token.Kind.negativeLookAheadAssertionBegin);
        } else if (consumingLookahead("?<=")) {
            return Token.create(Token.Kind.lookBehindAssertionBegin);
        } else if (consumingLookahead("?:")) {
            return Token.create(Token.Kind.nonCaptureGroupBegin);
        } else {
            registerCaptureGroup();
            return Token.create(Token.Kind.captureGroupBegin);
        }
    }

    private static final EnumSet<Token.Kind> QUANTIFIER_PREV = EnumSet.of(Token.Kind.charClass, Token.Kind.groupEnd, Token.Kind.backReference);

    private Token parseQuantifier(char c) throws RegexSyntaxException {
        int min;
        int max = -1;
        boolean greedy;
        if (c == '{') {
            final int resetIndex = index;
            BigInteger literalMin = parseDecimal();
            if (literalMin.compareTo(BigInteger.ZERO) < 0) {
                return countedRepetitionSyntaxError(resetIndex);
            }
            min = literalMin.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMin.intValue() : -1;
            if (consumingLookahead(",}")) {
                greedy = !consumingLookahead("?");
            } else if (consumingLookahead("}")) {
                max = min;
                greedy = !consumingLookahead("?");
            } else {
                BigInteger literalMax;
                if (!consumingLookahead(",") || (literalMax = parseDecimal()).compareTo(BigInteger.ZERO) < 0 || !consumingLookahead("}")) {
                    return countedRepetitionSyntaxError(resetIndex);
                }
                max = literalMax.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMax.intValue() : -1;
                greedy = !consumingLookahead("?");
                if (literalMin.compareTo(literalMax) > 0) {
                    throw syntaxError(ErrorMessages.QUANTIFIER_OUT_OF_ORDER);
                }
            }
        } else {
            greedy = !consumingLookahead("?");
            min = c == '+' ? 1 : 0;
            if (c == '?') {
                max = 1;
            }
        }
        if (lastToken == null) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        if (lastToken.kind == Token.Kind.quantifier) {
            throw syntaxError(ErrorMessages.QUANTIFIER_ON_QUANTIFIER);
        }
        if (!QUANTIFIER_PREV.contains(lastToken.kind)) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        return Token.createQuantifier(min, max, greedy);
    }

    private Token countedRepetitionSyntaxError(int resetIndex) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(ErrorMessages.INCOMPLETE_QUANTIFIER);
        }
        index = resetIndex;
        return charClass(CodePointSet.create('{'));
    }

    private Token parseCharClass() throws RegexSyntaxException {
        final boolean invert = consumingLookahead("^");
        CodePointSet curCharClass = CodePointSet.createEmpty();
        while (!atEnd()) {
            final char c = consumeChar();
            if (c == ']') {
                return charClass(curCharClass, invert);
            }
            parseCharClassRange(c, curCharClass);
        }
        throw syntaxError(ErrorMessages.UNMATCHED_LEFT_BRACKET);
    }

    private CodePointSet parseCharClassAtom(char c) throws RegexSyntaxException {
        if (c == '\\') {
            if (atEnd()) {
                throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
            }
            if (isEscapeCharClass(curChar())) {
                return parseEscapeCharClass(consumeChar());
            } else {
                return CodePointSet.create(parseEscapeChar(consumeChar(), true));
            }
        } else if (flags.isUnicode() && Character.isHighSurrogate(c)) {
            return CodePointSet.create(finishSurrogatePair(c));
        } else {
            return CodePointSet.create(c);
        }
    }

    private void parseCharClassRange(char c, CodePointSet curCharClass) throws RegexSyntaxException {
        CodePointSet firstAtom = parseCharClassAtom(c);
        if (consumingLookahead("-")) {
            if (atEnd() || lookahead("]")) {
                curCharClass.addSet(firstAtom);
                curCharClass.addRange(new CodePointRange((int) '-'));
            } else {
                CodePointSet secondAtom = parseCharClassAtom(consumeChar());
                // Runtime Semantics: CharacterRangeOrUnion(firstAtom, secondAtom)
                if (!firstAtom.matchesSingleChar() || !secondAtom.matchesSingleChar()) {
                    if (flags.isUnicode()) {
                        throw syntaxError(ErrorMessages.INVALID_CHARACTER_CLASS);
                    } else {
                        curCharClass.addSet(firstAtom);
                        curCharClass.addSet(secondAtom);
                        curCharClass.addRange(new CodePointRange((int) '-'));
                    }
                } else {
                    int firstChar = firstAtom.getRanges().get(0).lo;
                    int secondChar = secondAtom.getRanges().get(0).lo;
                    if (secondChar < firstChar) {
                        throw syntaxError(ErrorMessages.CHAR_CLASS_RANGE_OUT_OF_ORDER);
                    } else {
                        curCharClass.addRange(new CodePointRange(firstChar, secondChar));
                    }
                }
            }
        } else {
            curCharClass.addSet(firstAtom);
        }
    }

    private CodePointSet parseEscapeCharClass(char c) throws RegexSyntaxException {
        if (isPredefCharClass(c)) {
            return parsePredefCharClass(c);
        } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
            return parseUnicodeCharacterProperty(c == 'P');
        } else {
            throw new IllegalStateException();
        }
    }

    // Note that the CodePointSet returned by this function has already been
    // case-folded and negated.
    private CodePointSet parsePredefCharClass(char c) {
        switch (c) {
            case 's':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_WHITE_SPACE;
                } else {
                    return Constants.WHITE_SPACE;
                }
            case 'S':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_NON_WHITE_SPACE;
                } else {
                    return Constants.NON_WHITE_SPACE;
                }
            case 'd':
                return Constants.DIGITS;
            case 'D':
                return Constants.NON_DIGITS;
            case 'w':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.WORD_CHARS;
                }
            case 'W':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.NON_WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.NON_WORD_CHARS;
                }
            default:
                throw new IllegalStateException();
        }
    }

    private CodePointSet parseUnicodeCharacterProperty(boolean invert) throws RegexSyntaxException {
        if (!consumingLookahead("{")) {
            throw syntaxError(ErrorMessages.INVALID_UNICODE_PROPERTY);
        }
        StringBuilder propSpecBuilder = new StringBuilder();
        while (!atEnd() && curChar() != '}') {
            propSpecBuilder.append(consumeChar());
        }
        if (!consumingLookahead("}")) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_UNICODE_PROPERTY);
        }
        try {
            CodePointSet propertySet = UnicodeCharacterProperties.getProperty(propSpecBuilder.toString());
            return invert ? propertySet.createInverse() : propertySet;
        } catch (IllegalArgumentException e) {
            throw syntaxError(e.getMessage());
        }
    }

    private int parseEscapeChar(char c, boolean inCharClass) throws RegexSyntaxException {
        if (inCharClass && c == 'b') {
            return '\b';
        }
        switch (c) {
            case '0':
                if (flags.isUnicode() && isDecimal(curChar())) {
                    throw syntaxError(ErrorMessages.INVALID_ESCAPE);
                }
                // NOTE: Octal escapes are considered legacy by the ECMAScript spec and are
                // relegated to Annex B. Do we still want to support them?
                if (!flags.isUnicode() && !atEnd() && isOctal(curChar())) {
                    return parseOctal(0);
                }
                return '\0';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'v':
                return '\u000B';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
            case 'c':
                if (atEnd()) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                final char controlLetter = curChar();
                if (!flags.isUnicode() && (isDecimal(controlLetter) || controlLetter == '_') && inCharClass) {
                    advance();
                    return controlLetter % 32;
                }
                if (!('a' <= controlLetter && controlLetter <= 'z' || 'A' <= controlLetter && controlLetter <= 'Z')) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                advance();
                return Character.toUpperCase(controlLetter) - ('A' - 1);
            case 'u':
                if (flags.isUnicode() && consumingLookahead("{")) {
                    final int value = parseHex(1, Integer.MAX_VALUE, 0x10ffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (!consumingLookahead("}")) {
                        throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
                    }
                    return value;
                } else {
                    final int value = parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (flags.isUnicode() && Character.isHighSurrogate((char) value)) {
                        final int resetIndex = index;
                        if (consumingLookahead("\\u") && !lookahead("{")) {
                            final char lead = (char) value;
                            final char trail = (char) parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                            if (Character.isLowSurrogate(trail)) {
                                return Character.toCodePoint(lead, trail);
                            } else {
                                index = resetIndex;
                            }
                        } else {
                            index = resetIndex;
                        }
                    }
                    return value < 0 ? c : value;
                }
            case 'x':
                final int value = parseHex(2, 2, 0xff, ErrorMessages.INVALID_ESCAPE);
                return value < 0 ? c : value;
            case '-':
                if (!inCharClass) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
            default:
                // NOTE: Octal escapes are considered legacy by the ECMAScript spec and are
                // relegated to Annex B. Do we still want to support them?
                if (!flags.isUnicode() && isOctal(c)) {
                    return parseOctal(c - '0');
                }
                if (!SYNTAX_CHARS.get(c)) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
        }
    }

    private int finishSurrogatePair(char c) {
        assert flags.isUnicode() && Character.isHighSurrogate(c);
        if (!atEnd() && Character.isLowSurrogate(curChar())) {
            final char lead = c;
            final char trail = consumeChar();
            return Character.toCodePoint(lead, trail);
        } else {
            return c;
        }
    }

    private char escapeCharSyntaxError(char c, String msg) throws RegexSyntaxException {
        // NOTE: Throwing SyntaxErrors for invalid escapes only in Unicode mode is legacy behavior
        // (relegated to Annex B).
        if (flags.isUnicode()) {
            throw syntaxError(msg);
        }
        return c;
    }

    private BigInteger parseDecimal() {
        if (atEnd() || !isDecimal(curChar())) {
            return BigInteger.valueOf(-1);
        }
        return parseDecimal(BigInteger.ZERO);
    }

    private BigInteger parseDecimal(BigInteger firstDigit) {
        BigInteger ret = firstDigit;
        while (!atEnd() && isDecimal(curChar())) {
            ret = ret.multiply(BigInteger.TEN);
            ret = ret.add(BigInteger.valueOf(consumeChar() - '0'));
        }
        return ret;
    }

    /**
     * Parses a non-negative decimal integer. The value of the integer is clamped to
     * {@link Integer#MAX_VALUE}. For all {@code i} in {0,1,..,9}, {@code parseInteger(i)} is
     * equivalent to
     * {@code parseDecimal(BigInteger.valueOf(i)).max(BigInteger.valueOf(Integer.MAX_VALUE)}.
     * {@link #parseInteger(int)} should be faster than {@link #parseDecimal(java.math.BigInteger)}
     * because it does not have to go through {@link BigInteger}s.
     */
    private int parseInteger(int firstDigit) {
        int ret = firstDigit;
        // First, we consume all of the decimal digits that make up the integer.
        final int initialIndex = index;
        while (!atEnd() && isDecimal(curChar())) {
            advance();
        }
        final int terminalIndex = index;
        // Then, we parse the integer, stopping once we reach the limit Integer.MAX_VALUE.
        for (int i = initialIndex; i < terminalIndex; i++) {
            int nextDigit = pattern.charAt(i) - '0';
            if (ret > Integer.MAX_VALUE / 10) {
                return Integer.MAX_VALUE;
            }
            ret *= 10;
            if (ret > Integer.MAX_VALUE - nextDigit) {
                return Integer.MAX_VALUE;
            }
            ret += nextDigit;
        }
        return ret;
    }

    private int parseOctal(int firstDigit) {
        int ret = firstDigit;
        for (int i = 0; !atEnd() && isOctal(curChar()) && i < 2; i++) {
            if (ret * 8 > 255) {
                return ret;
            }
            ret *= 8;
            ret += consumeChar() - '0';
        }
        return ret;
    }

    private int parseHex(int minDigits, int maxDigits, int maxValue, String errorMsg) throws RegexSyntaxException {
        int ret = 0;
        int initialIndex = index;
        for (int i = 0; i < maxDigits; i++) {
            if (atEnd() || !isHex(curChar())) {
                if (i < minDigits) {
                    if (flags.isUnicode()) {
                        throw syntaxError(errorMsg);
                    } else {
                        // NOTE: Throwing SyntaxErrors for invalid escapes only in Unicode mode is
                        // legacy behavior (relegated to Annex B).
                        index = initialIndex;
                        return -1;
                    }
                } else {
                    break;
                }
            }
            final char c = consumeChar();
            ret *= 16;
            if (c >= 'a') {
                ret += c - ('a' - 10);
            } else if (c >= 'A') {
                ret += c - ('A' - 10);
            } else {
                ret += c - '0';
            }
            if (ret > maxValue) {
                throw syntaxError(errorMsg);
            }
        }
        return ret;
    }

    private static boolean isDecimal(char c) {
        return '0' <= c && c <= '9';
    }

    private static boolean isOctal(char c) {
        return '0' <= c && c <= '7';
    }

    private static boolean isHex(char c) {
        return '0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F';
    }

    private static boolean isPredefCharClass(char c) {
        return PREDEFINED_CHAR_CLASSES.get(c);
    }

    private boolean isEscapeCharClass(char c) {
        return isPredefCharClass(c) || (flags.isUnicode() && (c == 'p' || c == 'P'));
    }

    private RegexSyntaxException syntaxError(String msg) {
        return new RegexSyntaxException(pattern, flags, msg);
    }
}