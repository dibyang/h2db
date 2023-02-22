package org.h2.tools;

import org.h2.jdbc.JdbcSQLNonTransientConnectionException;
import org.h2.util.JdbcUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public class AutoFix {
  public boolean fix(String dir, String db) throws SQLException, IOException {
    File dbFile = Paths.get(dir, db + ".mv.db").toFile();
    if(dbFile.exists()){
      Recover recover = new Recover();
      recover.runTool("-dir",dir,"-db",db);
      File bakFile = Paths.get(dir, db + ".mv.db."+System.currentTimeMillis()).toFile();
      Files.move(dbFile.toPath(),bakFile.toPath());
      RunScript runScript = new RunScript();
      File scriptFile = Paths.get(dir, db + ".h2.sql").toFile();
      if(scriptFile.exists()) {
        runScript.runTool("-url", buildUrl(dir, db), "-script", scriptFile.getPath());
        return true;
      }
    }
    return false;
  }

  public boolean needFix(String dir, String db, String user, String password) throws SQLException {
    return needFix(buildUrl(dir, db), user, password);
  }

  private String buildUrl(String dir, String db) {
    StringBuilder builder = new StringBuilder("jdbc:h2:");
    builder.append(dir);
    if(!dir.endsWith("\\")&&!dir.endsWith("/")){
      builder.append("/");
    }
    builder.append(db);
    return builder.toString();
  }

  public boolean needFix(String url, String user, String password) throws SQLException {
    try (Connection conn = JdbcUtils.getConnection(null, url,user,password)){

    }catch(SQLException e){
      if(e instanceof JdbcSQLNonTransientConnectionException){
        if(e.getMessage().contains("File corrupted while reading record")){
          return true;
        }
      }else {
        throw e;
      }
    }
    return false;
  }

  public boolean fixDB(String dir, String db){
    try {
      AutoFix autoFix = new AutoFix();
      return autoFix.fix(dir,db);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  public Status autoFixDb(String dir,String db, String user, String password) throws SQLException {
    if(needFix(dir,db,user,password)){
      return fixDB(dir,db)?Status.success:Status.fail;
    }
    return Status.original;
  }

  public enum Status{
    /**
     * 修复失败
     */
    fail,
    /**
     * 修复成功
     */
    success,
    /**
     * 保持原样不用修复
     */
    original
  }

  public static void main(String[] args) {
    AutoFix autoFix = new AutoFix();
    Status status = null;
    try {
      status = autoFix.autoFixDb("D:\\h2db\\test1", "aiodb", "remote", "hhrhl2016");
      System.out.println("aiodb recover:"+status);
    } catch (SQLException e) {
      e.printStackTrace();
    }

  }
}
