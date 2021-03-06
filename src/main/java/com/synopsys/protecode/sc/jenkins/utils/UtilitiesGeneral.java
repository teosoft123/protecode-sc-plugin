/** *****************************************************************************
 * Copyright (c) 2017 Synopsys, Inc
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Synopsys, Inc - initial implementation and documentation
 ****************************************************************************** */
package com.synopsys.protecode.sc.jenkins.utils;

import com.synopsys.protecode.sc.jenkins.types.ConnectionStatus;
import com.synopsys.protecode.sc.jenkins.types.FileResult;
import com.synopsys.protecode.sc.jenkins.types.HttpTypes;
import com.synopsys.protecode.sc.jenkins.types.InternalTypes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import okhttp3.Headers;

public final class UtilitiesGeneral {

  /**
   * Checks connection status for errors. 
   *
   * @param connectionStatus the status object to check
   * @return true for connection ok, otherwise false
   */
  public static boolean connectionOk(ConnectionStatus connectionStatus) {
    if (connectionStatus.getError().isPresent()) {
      return false;
    }
    return !Integer.toString(connectionStatus.code()).startsWith("4");
  }

  /**
   * Checks the headers to determine whether Protecode SC is up and running
   *
   * @param headers headers from response
   * @param code the http response code
   * @return true if connection seems to be ok
   */
  public static boolean checkResponse(Headers headers, int code) {
    // TODO: Check if we should check the headers also
    return !Integer.toString(code).startsWith("4");
  }
  
  /**
   * Replaces spaces with underscore in the given line. This is used to format the query parameter
   * in the call to Protecode SC. The problem is that Protecode SC has a limited acceptable chars 
   * group.
   * 
   * TODO: Use perhaps a regexp maybe?
   * TODO: There is a slight possiblity that the user will give a file with underscores and a file 
   * with spaces which are otherwise identical. This will then not work. 
   * 
   * @param line The string to format
   * @return A string with spaces replaced with underscore
   */
  public static String replaceSpaceWithUnderscore(String line) {
    // TODO, use something which is certainly not used in other files. Underscore isn't good.
    // Currently underscore is accepted in protecode SC so it's in use.
    return line.replace(" ", "_");
  }
  
  public static String buildReportString(FileResult result) {    
    StringBuilder report = new StringBuilder();
    report.append("--------- Following files have vulnerabilities ---------\n");
    for (Map.Entry<String, Map<HttpTypes.Component, InternalTypes.VulnStatus>> file : result.getFiles().entrySet()) {
      if (file.getValue().values().stream().anyMatch((vulnStatus) -> (vulnStatus.untriagedVulnsCount()>0))) {
        report.append("\t").append(file.getKey()).append("\n");
      }
    }
    return report.toString();
  }

  /**
   * Method for getting a nicely formated timestamp.
   * TODO: This might already be implemented somewhere.
   * @return A formated time as string
   */
  public static String timestamp() {
    SimpleDateFormat sdfDate = new SimpleDateFormat("HH:mm:ss");//dd/MM/yyyy
    Date now = new Date();
    String strDate = sdfDate.format(now);
    return strDate;
  }
}
