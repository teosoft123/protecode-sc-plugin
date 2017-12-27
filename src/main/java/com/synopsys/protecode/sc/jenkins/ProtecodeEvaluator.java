/*******************************************************************************
* Copyright (c) 2017 Synopsys, Inc
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*    Synopsys, Inc - initial implementation and documentation
*******************************************************************************/
package com.synopsys.protecode.sc.jenkins;

import com.synopsys.protecode.sc.jenkins.types.InternalTypes;
import java.util.List;


public class ProtecodeEvaluator {
    public static boolean evaluate(
        List<InternalTypes.FileAndResult> results
    ) {
        //System.out.println("checking for vulncount-exact");
        return results.stream().anyMatch((fileAndResult) -> (!fileAndResult.verdict()));               
    }
}
