package com.trunk.rx.json.token;

public class JsonObjectStart extends BaseToken {

  private static final class Holder {
    private static final JsonObjectStart INSTANCE = new JsonObjectStart();
  }

  public static JsonObjectStart instance() {
    return Holder.INSTANCE;
  }

  private JsonObjectStart() {
    // do nothing
  }

  @Override
  public boolean isObjectStart() {
    return true;
  }

  @Override
  public String value() {
    return "{";
  }

  @Override
  public String toString() {
    return "JsonObjectStart{}";
  }
}
