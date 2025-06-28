package com.tio.mail.wing.result;

import java.util.List;

public class WhereClauseResult {
  private final String clause;
  private final List<Object> params;

  public WhereClauseResult(String clause, List<Object> params) {
    this.clause = clause;
    this.params = params;
  }

  public String getClause() {
    return clause;
  }

  public List<Object> getParams() {
    return params;
  }
}