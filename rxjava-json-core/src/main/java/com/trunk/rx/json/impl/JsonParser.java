package com.trunk.rx.json.impl;

import com.trunk.rx.json.JsonTokenEvent;
import com.trunk.rx.json.element.JsonNonExecutablePrefix;
import com.trunk.rx.json.exception.MalformedJsonException;
import com.trunk.rx.json.path.ArrayIndexToken;
import com.trunk.rx.json.path.JsonPath;
import com.trunk.rx.json.path.NoopToken;
import com.trunk.rx.json.path.ObjectToken;
import com.trunk.rx.json.path.RootToken;
import com.trunk.rx.json.token.JsonArray;
import com.trunk.rx.json.token.JsonBoolean;
import com.trunk.rx.json.token.JsonDocumentEnd;
import com.trunk.rx.json.token.JsonName;
import com.trunk.rx.json.token.JsonNull;
import com.trunk.rx.json.token.JsonNumber;
import com.trunk.rx.json.token.JsonObject;
import com.trunk.rx.json.token.JsonString;
import com.trunk.rx.json.token.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Producer;
import rx.Subscriber;
import rx.functions.Action0;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A streaming JSON parser based on Gson's
 * <a href="https://github.com/google/gson/blob/master/gson/src/main/java/com/google/gson/stream/JsonReader.java">JsonReader</a>.
 * (c) Google Inc
 */
public class JsonParser extends Subscriber<Character> {
  private static final Logger log = LoggerFactory.getLogger(JsonParser.class);

  private static final char[] NON_EXECUTE_PREFIX = JsonNonExecutablePrefix.PREFIX.toCharArray();
  private static final char BOM = '\uFEFF';

  private final boolean lenient;

  // is this the first character of the stream
  private boolean firstChar = true;

  // intermediate buffer
  private final char[] buffer = new char[1024];
  private int bufferOffset = 0;

  private int lineNumber = 0;
  private int columnNumber = 0;

  private NumberState numberState = null;

  private boolean inStringEscape = false;

  private final char[] unicodeEscapeBuffer = new char[4];
  private int unicodeEscapeBufferOffset = 0;
  private boolean inUnicodeEscape = false;

  private boolean hasSeparator = false;

  private char stringDelimiter = '"';

  /**
   * Used when a number or string overflows the buffer.
   */
  private StringBuilder bufferOverflow = new StringBuilder();

  /*
   * The nesting stack. Using a manual array rather than an ArrayList saves 20%.
   */
  private JsonScope[] stack = new JsonScope[32];
  private int stackSize = 0;

  private boolean inLineComment = false;
  private boolean inCComment = false;
  private boolean maybeStartComment = false;
  private boolean maybeEndComment = false;

  private boolean emitted = false;

  private AtomicBoolean started = new AtomicBoolean(false);
  private AtomicBoolean completed = new AtomicBoolean(false);
  private Queue<JsonTokenEvent> tokenBuffer = new ConcurrentLinkedDeque<>();
  private Action0 reenterProducer = () -> {};
  private AtomicReference<Throwable> error = new AtomicReference<>();

  {
    stack[stackSize++] = JsonScope.EMPTY_DOCUMENT;
  }

  /*
   * The path members. It corresponds directly to stack: At indices where the
   * stack contains an object (EMPTY_OBJECT, DANGLING_NAME or NONEMPTY_OBJECT),
   * pathNames contains the name at this scope. Where it contains an array
   * (EMPTY_ARRAY, NONEMPTY_ARRAY) pathIndices contains the current index in
   * that array. Otherwise the value is undefined, and we take advantage of that
   * by incrementing pathIndices when doing so isn't useful.
   */
  private JsonPath[] paths = new JsonPath[32];

  public JsonParser(boolean lenient) {
    this.lenient = lenient;
    request(0);
  }

  public boolean completed() {
    return completed.get();
  }

  public boolean started() {
    return started.get();
  }

  public boolean isEmpty() {
    return tokenBuffer.isEmpty();
  }

  public JsonTokenEvent poll() {
    return tokenBuffer.poll();
  }

  public void requestMore() {
    request(1);
  }

  public void reenterProducer(Action0 f) {
    reenterProducer = f;
  }

  public Throwable error() {
    return error.get();
  }

  @Override
  public void setProducer(Producer p) {
    super.setProducer(p);
    started.set(true);
    reenterProducer.call();
  }

  @Override
  public void onCompleted() {
      if (maybeStartComment) {
        completeWithError(syntaxError("Unexpected trailing slash"));
        return;
      }
      if ((currentScope() == JsonScope.BARE_VALUE || currentScope() == JsonScope.NUMBER) &&
          (bufferOverflow.length() > 0 || bufferOffset > 0)) {
        Optional<JsonToken> token = getCurrentValueAndResetBuffer();
        token.ifPresent(t -> {
          popScope();
          emitDownstream(t);
          maybeEmitDocumentEnd();
        });
        if (!token.isPresent()) {
          completeWithError(syntaxError("Invalid bare token"));
          return;
        }
      }
      if (
        currentScope() == JsonScope.NONEMPTY_DOCUMENT ||
        (currentScope() == JsonScope.EMPTY_DOCUMENT && lenient)
      ) {
        complete();
      } else if (currentScope() == JsonScope.EMPTY_DOCUMENT) {
        completeWithError(syntaxError("Empty JSON"));
      } else {
        completeWithError(syntaxError("Expected " + getExpected()));
      }
    }

  private void complete() {
    trace(" - complete");
    completed.set(true);
    reenterProducer.call();
  }

  private void completeWithError(Throwable t) {
    trace(" - error: {}", t.getMessage());
    error.set(t);
    complete();
  }

  @Override
  public void onError(Throwable t) {
    completeWithError(t);
  }

  @Override
  public void onNext(Character c) {
    try {
      trace("{}", c);

      emitted = false;

      started.set(true);

      if (completed()) {
        return;
      }

      if(!captureComment(c)) {
        doOnNext(c);
      }

      // so we only count characters once
      if (c == '\n') {
        ++lineNumber;
        columnNumber = 0;
      } else {
        ++columnNumber;
      }
      firstChar = false;

      if (!emitted) {
        requestMore();
      }

      reenterProducer.call();
    } catch (Throwable t) {
      log.warn("Unexpected error", t);
      completeWithError(t);
    }
  }

  private void doOnNext(char c) {
    trace(" - {}\t{}", currentStack(), getPath());
    JsonScope scope = currentScope();
    if (scope == JsonScope.EMPTY_DOCUMENT) {
      handleEmptyDocument(c);
    } else if (scope == JsonScope.NONEMPTY_DOCUMENT) {
      handleNonEmptyDocument(c);
    } else if (scope == JsonScope.EMPTY_ARRAY) {
      handleEmptyArray(c);
    } else if (scope == JsonScope.NONEMPTY_ARRAY) {
      handleNonEmptyArray(c);
    } else if (scope == JsonScope.EMPTY_OBJECT) {
      handleEmptyObject(c);
    } else if (scope == JsonScope.NONEMPTY_OBJECT) {
      handleNonEmptyObject(c);
    } else if (scope == JsonScope.DANGLING_NAME) {
      handleName(c);
    } else if (scope == JsonScope.BARE_VALUE) {
      handleBareValue(c);
    } else if (scope == JsonScope.NUMBER) {
      handleNumber(c);
    } else if (scope == JsonScope.QUOTED_STRING) {
      handleString(c);
    }
  }

  private void handleName(char c) {
    if (isWhitespace(c)) {
      skipWhitespace();
    } else if (hasSeparator && lenient && c == '>') {
      hasSeparator = true;
    } else if (hasSeparator) {
      if (c == '[') {
        startArray(JsonScope.NONEMPTY_OBJECT);
      } else if (c == '{') {
        startObject(JsonScope.NONEMPTY_OBJECT);
      } else if (c == '}' || c == ']' || c == ':' || c == '=' || c == ',' || c == ';') {
        completeWithError(syntaxError("Expected value"));
      } else {
        startSimpleValue(c, JsonScope.NONEMPTY_OBJECT);
      }
      hasSeparator = false;
    } else if (c == ':' || (lenient && c == '=')) {
      hasSeparator = true;
    } else {
      completeWithError(syntaxError("Expected object separator"));
    }
  }

  private void handleNonEmptyObject(char c) {
    if (isWhitespace(c)) {
      skipWhitespace();
    } else if (hasSeparator) {
      if (c != '"' && c != '\'' && isControlCharacter(c)) {
        completeWithError(syntaxError("Expected name"));
      } else {
        startName(c);
        hasSeparator = false;
      }
    } else if (c == '}') {
      popScope(); // order effects jsonPath
      emitDownstream(JsonObject.end());
      maybeEmitDocumentEnd();
    } else if (c == ',' || (lenient && c == ';')) {
      hasSeparator = true;
    } else {
      completeWithError(syntaxError("Expected list separator"));
    }
  }

  private void handleEmptyObject(char c) {
    if (isWhitespace(c)) {
      skipWhitespace();
    } else if (c == '}') {
      popScope();
      emitDownstream(JsonObject.end());
      maybeEmitDocumentEnd();
    } else if (c == '{' || c == '[' || c == ']' || c == ':' || c == '=' || c == ',' || c == ';') {
      completeWithError(syntaxError("Expected name"));
    } else {
      startName(c);
    }
  }

  private void startName(char c) {
    startSimpleValue(c, JsonScope.DANGLING_NAME);
  }

  private void handleNonEmptyArray(char c) {
    if (isWhitespace(c)) {
      skipWhitespace();
    } else if (hasSeparator) {
      if (lenient && (c == ',' || c == ';')) {
        incrementPathIndex();
        emitDownstream(JsonNull.instance());
      } else if (lenient && c == ']') {
        hasSeparator = false;
        popScope();
        emitDownstream(JsonNull.instance());
        emitDownstream(JsonArray.end());
        maybeEmitDocumentEnd();
      } else if (c == '[') {
        startArray(JsonScope.NONEMPTY_ARRAY);
      } else if (c == '{') {
        startObject(JsonScope.NONEMPTY_ARRAY);
      } else if (c == '}' || c == ':' || c == '=' || c == ',' || c == ';') {
        completeWithError(syntaxError("Expected value"));
      } else {
        hasSeparator = false;
        startSimpleValue(c, JsonScope.NONEMPTY_ARRAY);
      }
    } else if (c == ']') {
      popScope(); // order effects jsonPath
      emitDownstream(JsonArray.end());
      maybeEmitDocumentEnd();
    } else if (c == ',' || (lenient && c == ';')) {
      hasSeparator = true;
      incrementPathIndex();
    } else {
      completeWithError(syntaxError("Expected list separator"));
    }
  }

  private void handleEmptyArray(char c) {
    if (c == ']') {
      popScope();
      emitDownstream(JsonArray.end());
      maybeEmitDocumentEnd();
    } else if (isWhitespace(c)) {
      skipWhitespace();
    } else if (c == '[') {
      startArray(JsonScope.NONEMPTY_ARRAY);
    } else if (c == '{') {
      startObject(JsonScope.NONEMPTY_ARRAY);
    } else if (lenient && (c == ',' || c == ';')) {
      setScope(JsonScope.NONEMPTY_ARRAY);
      hasSeparator = true;
      incrementPathIndex();
      emitDownstream(JsonNull.instance());
    } else if (c == '}' || c == ':' || c == '=' || c == ',' || c == ';') {
      completeWithError(syntaxError("Expected value"));
    } else {
      startSimpleValue(c, JsonScope.NONEMPTY_ARRAY);
    }
  }

  private void handleNumber(char c) {
    Optional<NumberState> transition = getNumberTransition(c);
    if (isWhitespace(c) || isControlCharacter(c)) {
      emitBareValueOrError(c);
    } else if (transition.isPresent()) {
      transition.ifPresent(newState -> numberState = newState);
      appendBuffer(c);
    } else {
      setScope(JsonScope.BARE_VALUE);
      handleBareValue(c);
    }
  }

  private void handleString(char c) {
    if (inStringEscape) {
      if (c == 'u') {
        inUnicodeEscape = true;
        unicodeEscapeBufferOffset = 0;
      } else if (c == 't') {
        appendBuffer('\t');
      } else if (c == 'b') {
        appendBuffer('\b');
      } else if (c == 'n') {
        appendBuffer('\n');
      } else if (c == 'r') {
        appendBuffer('\r');
      } else if (c == 'f') {
        appendBuffer('\f');
      } else if (c == '\'') {
        appendBuffer('\'');
      } else if (c == '"') {
        appendBuffer('"');
      } else if (c == '\\') {
        appendBuffer('\\');
      } else if (c == '\n') {
        appendBuffer('\n');
      } else {
        appendBuffer(c);
      }
      inStringEscape = false;
    } else if (inUnicodeEscape) {
      if (unicodeEscapeBufferOffset < unicodeEscapeBuffer.length && isHex(c)) {
        unicodeEscapeBuffer[unicodeEscapeBufferOffset] = c;
        ++unicodeEscapeBufferOffset;
        if (unicodeEscapeBufferOffset == unicodeEscapeBuffer.length) {
          appendBuffer(getEscapedUnicode(unicodeEscapeBuffer));
          inUnicodeEscape = false;
        }
      } else {
        completeWithError(syntaxError("Invalid unicode escape sequence"));
      }
    } else if (c == '\\') {
      inStringEscape = true;
    } else if (c == stringDelimiter) {
      Optional<JsonToken> token = getCurrentValueAndResetBuffer();
      if (token.isPresent()) {
        token.ifPresent(t -> {
          popScope();
          emitDownstream(t);
          maybeEmitDocumentEnd();
        });
      } else {
        completeWithError(syntaxError("Invalid value"));
      }
    } else {
      appendBuffer(c);
    }
  }

  public static char getEscapedUnicode(char[] unicodeEscapeBuffer) {
    char result = 0;
    for (char c : unicodeEscapeBuffer) {
      result <<= 4;
      if (c >= '0' && c <= '9') {
        result += (c - '0');
      } else if (c >= 'a' && c <= 'f') {
        result += (c - 'a' + 10);
      } else if (c >= 'A' && c <= 'F') {
        result += (c - 'A' + 10);
      }
    }
    return result;
  }

  private void handleBareValue(char c) {
    if (isWhitespace(c) || isControlCharacter(c)) {
      emitBareValueOrError(c);
    } else {
      appendBuffer(c);
    }
  }

  private void handleNonEmptyDocument(char c) {
    if (isWhitespace(c)) {
      skipWhitespace();
    } else if (lenient) {
      handleEmptyDocument(c);
    } else {
      completeWithError(syntaxError("Unexpected data after document completed"));
    }
  }

  private void handleEmptyDocument(char c) {
    if (firstChar && c == BOM) {
      --columnNumber;
    } else if (lenient && c == '\n' && isNonExecutePrefix(c)) {
      resetBuffer();
    } else if (lenient && isNonExecutePrefix(c)) {
      appendBuffer(c);
    } else if (isWhitespace(c)) {
      skipWhitespace();
    } else if (c == '{') {
      startObject(JsonScope.NONEMPTY_DOCUMENT);
    } else if (c == '[') {
      startArray(JsonScope.NONEMPTY_DOCUMENT);
    } else {
      startSimpleValue(c, JsonScope.NONEMPTY_DOCUMENT);
    }
  }

  private void maybeEmitDocumentEnd() {
    if (currentScope() == JsonScope.NONEMPTY_DOCUMENT) {
      emitDownstream(JsonDocumentEnd.instance(), NoopToken.instance());
      if (lenient) {
        setScope(JsonScope.EMPTY_DOCUMENT);
      }
    }
  }

  private void emitBareValueOrError(char c) {
    Optional<JsonToken> token = getCurrentValueAndResetBuffer();
    if (token.isPresent()) {
      token.ifPresent(t -> {
        emitDownstream(t);
        popScope();
      });
      maybeEmitDocumentEnd();
      doOnNext(c);
    } else {
      completeWithError(syntaxError("Invalid value"));
    }
  }

  private void skipWhitespace() {
    // do nothing
  }

  private void startObject(JsonScope nonEmptyScope) {
    hasSeparator = false;
    setScope(nonEmptyScope);
    pushScope(JsonScope.EMPTY_OBJECT);
    emitDownstream(JsonObject.start());
  }

  private void startArray(JsonScope nonEmptyScope) {
    hasSeparator = false;
    setScope(nonEmptyScope);
    pushScope(JsonScope.EMPTY_ARRAY);
    resetPathIndex();
    emitDownstream(JsonArray.start());
  }

  private void startSimpleValue(char c, JsonScope nonEmptyScope) {
    if (c == '"' || (lenient && c == '\'')) {
      startQuotedString(c, nonEmptyScope);
    } else if (isNumberStart(c)) {
      startNumber(c, nonEmptyScope);
    } else {
      startBareValue(c, nonEmptyScope);
    }
  }

  private void startNumber(char c, JsonScope nonEmptyScope) {
    appendBuffer(c);
    if (c == '-') {
      numberState = NumberState.NUMBER_CHAR_SIGN;
    } else if (c == '0') {
      numberState = NumberState.NUMBER_CHAR_NONE;
    } else {
      numberState = NumberState.NUMBER_CHAR_DIGIT;
    }
    setScope(nonEmptyScope);
    pushScope(JsonScope.NUMBER);
  }

  private boolean captureComment(char c) {
    if (!lenient) {
      return false;
    }
    if (inLineComment) {
      if (c == '\n') {
        inLineComment = false;
      }
      return true;
    }
    if (inCComment) {
      if (c == '*') {
        maybeEndComment = true;
      } else if (maybeEndComment && c == '/') {
        inCComment = false;
        maybeEndComment = false;
      } else {
        maybeEndComment = false;
      }
      return true;
    }
    if (maybeStartComment) {
      maybeStartComment = false;
      if (c == '/') {
        inLineComment = true;
        return true;
      } else if (c == '*') {
        inCComment = true;
        return true;
      }
      doOnNext('/'); // replace missing slash
    } else if (currentScope() != JsonScope.QUOTED_STRING) {
      if (c == '#') {
        inLineComment = true;
        return true;
      } else if (c == '/') {
        maybeStartComment = true;
        return true;
      }
    }
    maybeStartComment = false;
    return false;
  }

  private boolean isNumberStart(char c) {return isDigit(c) || c == '-';}

  private void startQuotedString(char c, JsonScope nonEmptyScope) {
    stringDelimiter = c;
    setScope(nonEmptyScope);
    pushScope(JsonScope.QUOTED_STRING);
  }

  private void startBareValue(char c, JsonScope nonEmptyScope) {
    if (isControlCharacter(c) && c != '/' && c != '#') {
      completeWithError(syntaxError("Invalid value"));
    } else {
      setScope(nonEmptyScope);
      pushScope(JsonScope.BARE_VALUE);
      appendBuffer(c);
    }
  }

  private boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private boolean isHex(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private Optional<NumberState> getNumberTransition(char c) {
    if (numberState == NumberState.NUMBER_CHAR_SIGN && c == '0') {
      return Optional.of(NumberState.NUMBER_CHAR_NONE);
    } else if (numberState == NumberState.NUMBER_CHAR_SIGN && c >= '1' && c <= '9') {
      return Optional.of(NumberState.NUMBER_CHAR_DIGIT);

    } else if (numberState == NumberState.NUMBER_CHAR_NONE && c == '.') {
      return Optional.of(NumberState.NUMBER_CHAR_DECIMAL);
    } else if (numberState == NumberState.NUMBER_CHAR_NONE && (c == 'e' || c == 'E')) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_E);

    } else if (numberState == NumberState.NUMBER_CHAR_DIGIT && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_DIGIT);
    } else if (numberState == NumberState.NUMBER_CHAR_DIGIT && c == '.') {
      return Optional.of(NumberState.NUMBER_CHAR_DECIMAL);
    } else if (numberState == NumberState.NUMBER_CHAR_DIGIT && (c == 'e' || c == 'E')) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_E);

    } else if (numberState == NumberState.NUMBER_CHAR_DECIMAL && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_FRACTION_DIGIT);

    } else if (numberState == NumberState.NUMBER_CHAR_FRACTION_DIGIT && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_FRACTION_DIGIT);
    } else if (numberState == NumberState.NUMBER_CHAR_FRACTION_DIGIT && (c == 'e' || c == 'E')) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_E);

    } else if (numberState == NumberState.NUMBER_CHAR_EXP_E && (c == '+' || c == '-')) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_SIGN);
    } else if (numberState == NumberState.NUMBER_CHAR_EXP_E && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_DIGIT);

    } else if (numberState == NumberState.NUMBER_CHAR_EXP_SIGN && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_DIGIT);

    } else if (numberState == NumberState.NUMBER_CHAR_EXP_DIGIT && isDigit(c)) {
      return Optional.of(NumberState.NUMBER_CHAR_EXP_DIGIT);
    }

    return Optional.empty();
  }

  private boolean isWhitespace(char c) {return c <= ' ';}

  private boolean isControlCharacter(char c) {
    return
      c == '\\' ||
      c == '{' ||
      c == '[' ||
      c == ']' ||
      c == '}' ||
      c == ',' ||
      c == ':' ||
      c == ';' ||
      c == '=' ||
      c == '\'' ||
      c == '"';
  }

  private boolean isNonExecutePrefix(char c) {
    boolean result = bufferOffset <= 4;
    int i = 0;
    for (; i < bufferOffset; ++i) {
      result = result && buffer[i] == NON_EXECUTE_PREFIX[i];
    }
    return result && c == NON_EXECUTE_PREFIX[i];
  }

  private void appendBuffer(char c) {
    if (bufferOffset == buffer.length - 1) {
      bufferOverflow.append(buffer, 0, bufferOffset);
      bufferOffset = 0;
    }
    buffer[bufferOffset] = c;
    ++bufferOffset;
  }

  /**
   * Return the current number, string, bool, null.
   * Does not check leniency or closure.
   */
  private Optional<JsonToken> getCurrentValueAndResetBuffer() {
    JsonScope valueScope = currentScope();
    JsonScope parentScope = parentScope();
    String value = bufferOverflow.append(buffer, 0, bufferOffset).toString();
    resetBuffer();
    if (parentScope == JsonScope.DANGLING_NAME && (lenient || valueScope == JsonScope.QUOTED_STRING)) {
      paths[stackSize - 2] = ObjectToken.of(value);
      return Optional.of(JsonName.of(value));
    } else if (parentScope == JsonScope.NONEMPTY_DOCUMENT && valueScope == JsonScope.BARE_VALUE && value.startsWith("/")) {
      return Optional.empty();
    } else if (valueScope == JsonScope.NUMBER && validNumberState()) {
      return Optional.of(JsonNumber.of(value));
    } else if (valueScope == JsonScope.QUOTED_STRING) {
      return Optional.of(JsonString.of(value));
    } else if (value.equalsIgnoreCase("true")) {
      return Optional.of(JsonBoolean.True());
    } else if (value.equalsIgnoreCase("false")) {
      return Optional.of(JsonBoolean.False());
    } else if (value.equalsIgnoreCase("null")) {
      return Optional.of(JsonNull.instance());
    } else if (lenient && (value.equals("NaN") || value.equals("-Infinity") || value.equals("Infinity"))) {
      return Optional.of(JsonNumber.of(value));
    } else if (lenient) {
      return Optional.of(JsonString.of(value));
    } else {
      return Optional.empty();
    }
  }

  private boolean validNumberState() {
    return
      numberState == NumberState.NUMBER_CHAR_NONE ||
      numberState == NumberState.NUMBER_CHAR_DIGIT ||
      numberState == NumberState.NUMBER_CHAR_FRACTION_DIGIT ||
      numberState == NumberState.NUMBER_CHAR_EXP_DIGIT;
  }

  private String getExpected() {
    JsonScope scope = currentScope();
    if (scope == JsonScope.EMPTY_DOCUMENT) {
      return "any value";
    } else if (scope == JsonScope.NONEMPTY_DOCUMENT) {
      return lenient ? "end of stream or any value" : "end of stream";
    } else if (scope == JsonScope.EMPTY_ARRAY) {
      return "any value or end of array";
    } else if (scope == JsonScope.NONEMPTY_ARRAY) {
      return hasSeparator ? "any value" : "separator or end of array";
    } else if (scope == JsonScope.EMPTY_OBJECT) {
      return "any value or end of object";
    } else if (scope == JsonScope.NONEMPTY_OBJECT) {
      return hasSeparator ? "any value" : "separator or end of object";
    } else if (scope == JsonScope.DANGLING_NAME) {
      return "name-value separator";
    } else if (scope == JsonScope.BARE_VALUE) {
      return "Any value";
    } else if (scope == JsonScope.NUMBER) {
      return "numeric value";
    } else if (scope == JsonScope.QUOTED_STRING) {
      return "string data or close quote";
    }
    return "any value";
  }

  private void resetBuffer() {
    bufferOffset = 0;
    bufferOverflow = new StringBuilder();
  }

  private void incrementPathIndex() {
    paths[stackSize - 1] = ((ArrayIndexToken) paths[stackSize - 1]).increment();
  }

  private void resetPathIndex() {
    paths[stackSize - 1] = ArrayIndexToken.of(0);
  }

  private JsonScope currentScope() {
    return stack[stackSize-1];
  }

  private JsonScope parentScope() {
    return stackSize > 1 ? stack[stackSize-2] : null;
  }

  private void pushScope(JsonScope newTop) {
    if (stackSize == stack.length) {
      JsonScope[] newStack = new JsonScope[stackSize * 2];
      JsonPath[] newPaths = new JsonPath[stackSize * 2];
      System.arraycopy(stack, 0, newStack, 0, stackSize);
      System.arraycopy(paths, 0, newPaths, 0, stackSize);
      stack = newStack;
      paths = newPaths;
    }
    stack[stackSize++] = newTop;
  }

  private void popScope() {
    stackSize -= 1;
  }

  private void setScope(JsonScope scope) {
    stack[stackSize - 1] = scope;
  }

  /**
   * Return a new MalformedJsonException with the given message
   * and a context snippet with this parsers's content.
   */
  private MalformedJsonException syntaxError(String message) {
    return new MalformedJsonException(
      message + " at line " + getLineNumber() +
        " column " + getColumnNumber() + " path " + getPath()
    );
  }

  private int getLineNumber() {
    return lineNumber + 1;
  }

  private int getColumnNumber() {
    return columnNumber + 1;
  }

  private String currentStack() {
    StringBuilder result = new StringBuilder();
    for (int i = 0, size = stackSize; i < size; i++) {
      result.append(stack[i]).append(' ');
    }
    return result.toString();
  }

  private void emitDownstream(JsonToken token) {
    emitDownstream(token, getPath());
  }

  private void emitDownstream(JsonToken token, JsonPath path) {
    emitted = true;
    trace(" - emitted {} at {}", token, path);
    tokenBuffer.add(new JsonTokenEvent(token, path));
    reenterProducer.call();
  }

  private void trace(String message, Object... arguments) {
    log.trace(message, arguments);
  }

  /**
   * Returns a <a href="http://goessner.net/articles/JsonPath/">JsonPath</a> to
   * the current location in the JSON value.
   */
  private JsonPath getPath() {
    List<JsonPath> tokens = new ArrayList<>();
    tokens.add(RootToken.instance());
    for (int i = 0, size = stackSize; i < size; i++) {
      if (stack[i] == JsonScope.NONEMPTY_OBJECT || stack[i] == JsonScope.NONEMPTY_ARRAY) {
        tokens.add(paths[i]);
      }
    }
    return JsonPath.from(tokens);
  }
}
