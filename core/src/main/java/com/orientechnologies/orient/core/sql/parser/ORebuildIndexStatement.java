/* Generated By:JJTree: Do not edit this line. ORebuildIndexStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.Map;

public class ORebuildIndexStatement extends OStatement {

  protected boolean all = false;
  protected OIndexName name;

  public ORebuildIndexStatement(int id) {
    super(id);
  }

  public ORebuildIndexStatement(OrientSql p, int id) {
    super(p, id);
  }

  @Override public void toString(Map<Object, Object> params, StringBuilder builder) {
    builder.append("REBUILD INDEX ");
    if (all) {
      builder.append("*");
    } else {
      name.toString(params, builder);
    }
  }
}
/* JavaCC - OriginalChecksum=baca3c54112f1c08700ebdb691fa85bd (do not edit this line) */
