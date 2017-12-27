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


import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.synopsys.protecode.sc.jenkins.ProtecodeScService;
import com.synopsys.protecode.sc.jenkins.interfaces.Listeners.ScanService;
import com.synopsys.protecode.sc.jenkins.types.HttpTypes.ScanResultResponse;
import com.synopsys.protecode.sc.jenkins.types.HttpTypes.UploadResponse;
import com.synopsys.protecode.sc.jenkins.types.InternalTypes.FileAndResult;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.Run.Artifact;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import lombok.Getter;
import lombok.Setter;
import net.sf.json.JSONObject;
import okhttp3.MediaType;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class ProtecodeScPlugin extends Builder implements SimpleBuildStep {
    @Getter private String credentialsId;
    @Getter private String protecodeScGroup;    
    @Getter private String filesToScanDirectory;
    //@Getter private boolean convertToSummary = true;
    private boolean convertToSummary = true;
    @Getter private boolean failIfVulns;
    @Getter private boolean leaveArtifacts;
    @Getter private int scanTimeout;  
    // don't access service directly, use service(). It checks whether this exists
    private ProtecodeScService service = null;
    
    // Below used in the scan process
    private final List<FileAndResult> results = new ArrayList<>(); 
    private long stopAt = 0;
    
    // used for printing to the jenkins console
    PrintStream log = null;
    TaskListener listener = null;
    
    public static final String REPORT_DIRECTORY = "reports";
    public static final String NO_ERROR = "";
    
    @DataBoundConstructor   
    public ProtecodeScPlugin(
        String credentialsId, 
        String protecodeScGroup,        
        String filesToScanDirectory, 
        boolean convertToSummary,
        boolean failIfVulns,
        boolean leaveArtifacts, 
        int scanTimeout
    ) {
        this.credentialsId = credentialsId;
        this.protecodeScGroup = protecodeScGroup;
        this.filesToScanDirectory = filesToScanDirectory;
        this.convertToSummary = convertToSummary;
        this.failIfVulns = failIfVulns;
        this.leaveArtifacts = leaveArtifacts;
        this.scanTimeout = scanTimeout > 10 ? scanTimeout : 10;
    }       
    
    @DataBoundSetter // Groovy
    public void setConvertToSummary(boolean convertToSummary) {
        this.convertToSummary = convertToSummary;
    }
    
    @CheckForNull
    public boolean getConvertToSummary() {
        return convertToSummary;
    }          
    
    private ProtecodeScService service() {
        // TODO: Add check that service is ok
        if (service == null) {
            try {
            service = ProtecodeScService.getInstance(
                credentialsId,
                new URL(getDescriptor().getProtecodeScHost()),
                !getDescriptor().isDontCheckCert()
            );
            } catch (MalformedURLException e) {
                // this url is already cleaned when getting it from the configuration page
            }
        }
        return service;
    }
    
    private boolean isTimeout() {
        return System.currentTimeMillis() > stopAt;
    }
    
    private void startPollTimer() {
        // stopAt is set to be the moment we don't try to poll anymore
        stopAt = System.currentTimeMillis() + 1000L * 60 * scanTimeout;
    }
    
    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) 
        throws InterruptedException, IOException 
    {                
        log = listener.getLogger();
        this.listener = listener;
        doPerform(run, workspace);
    }
    
    @Override    
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, 
        BuildListener listener) throws InterruptedException, IOException 
    {         
        log = listener.getLogger();
        this.listener = (TaskListener) listener;
        return doPerform(build, build.getWorkspace());
    }
    
    public boolean doPerform(Run<?, ?> run, FilePath workspace) 
        throws IOException, InterruptedException 
    {        
        // use shortened word to distinguish from possibly null service
        ProtecodeScService serv = service();
        List<ReadableFile> filesToScan = Utils.getFiles(
            filesToScanDirectory, 
            workspace, 
            run, 
            listener
        );
        
        if (filesToScan.isEmpty()) {
            // no files to scan, no failure
            return true;
        } else {
            log.println("Directory for files to scan was not empty, proceding");            
        }
        
        log.println("Sending files");        
        
        for (ReadableFile file: filesToScan) {
            log.println("Sending file: " + file.name());            
            serv.scan(
                protecodeScGroup, 
                file.name(), 
                new StreamRequestBody
                (
                    MediaType.parse("application/octet-stream"), 
                    file
                ), 
                new ScanService() {
                    @Override
                    public void processUploadResult(UploadResponse result) {
                        addUploadResponse(log, file.name(), result, NO_ERROR);
                    }
                    @Override
                    public void setError(String reason) {
                        // TODO: use Optional
                        addUploadResponse(log, file.name(), null, reason);
                    }
                }
            );
            Thread.sleep(500); // we don't want to overload anything
        }
        
        // Then we wait and continue only when we have as many UploadResponses as we have 
        // filesToScan. Sad but true       
        log.println("Calling wait");
        waitForUploadResponses(filesToScan.size(), log);            
        log.println("Wait over");
        
        // start polling for reponses to scans                
        if (!poll()) {
            // maybe we were interrupted or something failed, ending phase            
            return false;
        }
        
        //evaluate
        boolean verdict = ProtecodeEvaluator.evaluate(results);
        
        // make results
        ReportBuilder.report(results, listener, REPORT_DIRECTORY, workspace);
            
        // summarise
        if(convertToSummary) {
            ReportBuilder.makeSummary(results, run, listener, REPORT_DIRECTORY, workspace);
        }
        if (failIfVulns) {
            return verdict;
        }
        return true;
    }
    
     /**
     * Called by the lamdas given to upload rest calls
     * @param response The responses fetched from Protecode SC
     */
    private void addUploadResponse(PrintStream log, String name, UploadResponse response, String error) {
        if (NO_ERROR.equals(error)) {
            log.println("adding upload response for file: " + name);
            results.add(new FileAndResult(name, response));
        } else {
            log.println("adding upload response with ERROR for file: " + name);
            results.add(new FileAndResult(name, error));
        }
    }
    
    /**
     * TODO clean up depth, move logic to other methods.
     * @param listener 
     */
    private boolean poll() {
        startPollTimer();        
        // use shortened word to distinguish from possibly null service
        ProtecodeScService serv = service();
        do {
            log.println("Starting result polling and fetching");
            if (isTimeout()) {
                return false;
            }
            results.forEach((FileAndResult fileAndResult) -> {
                // TODO: Add check if the result never was reached
                if (!fileAndResult.hasScanResponse()) {  // if this return true, we can ignore the fileAndResult
                    log.println("no result received yet for " + fileAndResult.getFilename());
                    if (fileAndResult.uploadHTTPStatus() == 200) {
                        log.println("HTTP Status for " + fileAndResult.getFilename() + " is 200, proceding");
                        if ("R".equals(fileAndResult.getState())) {
                            log.println("status 'Ready' for " + fileAndResult.getFilename());
                            if (!fileAndResult.isResultBeingFetched()) {
                                log.println("Result for " + fileAndResult.getFilename() + " hasn't been asked for yet, getting.");
                                fileAndResult.setResultBeingFetched(true);
                                serv.scanResult(
                                    fileAndResult.getUploadResponse().getResults().getSha1sum(),
                                    (ScanResultResponse scanResult) -> {
                                        log.println("setting result for file: " + fileAndResult.getFilename());
                                        fileAndResult.setResultResponse(scanResult);
                                    }
                                );
                            }
                        } else {
                            log.println("status NOT 'Ready' for " + fileAndResult.getFilename() + ", polling.");
                            serv.poll(
                                fileAndResult.getUploadResponse().getResults().getId(),
                                (UploadResponse uploadResponse) -> {
                                    log.println("server responded for poll of " + fileAndResult.getFilename() + ": " + uploadResponse.getResults().getStatus());
                                    fileAndResult.setUploadResponse(uploadResponse);
                                }
                            );
                        }
                    } else {
                        listener.error("Status code for file upload: '" + fileAndResult.getFilename() +
                            "' was " + fileAndResult.uploadHTTPStatus());
                    }
                }
                try {
                    Thread.sleep(500); // we don't want to overload anything
                } catch (InterruptedException ex) {
                    Logger.getLogger(ProtecodeScPlugin.class.getName()).log(Level.SEVERE, null, ex);
                }
            });
            
            if (allNotReady()) {
                try {
                    log.println("Main thread sleeping for a moment");
                    Thread.sleep(15 * 1000);
                } catch (InterruptedException e) {
                    log.println("Sleep was interrupted, anding wait and stopping build");
                    return false;
                }            
            }
        } while (allNotReady());
        log.println("All results in, returning to perform()");
        return true;
    }
    
    private boolean allNotReady() {
        return results.stream().anyMatch((fileAndResult) -> (!fileAndResult.hasScanResponse()));
    }
    
    /**
     * Waits until all upload results are in. Returns only then.
     * @param fileCount How many files were uploaded
     * @param log for printing to Jenkins build console.
     */
    private void waitForUploadResponses(int fileCount, PrintStream log) {
        log.println("Starting wait");
        boolean waitForResponses = true;
        // TODO: Add timeout since some files get no reponse from protecode
        while (waitForResponses) {                   
            try {                
                Thread.sleep(30 * 1000);
                // TODO: remove print after testing
                log.println("Tick - remove this");
                if (results.size() >= fileCount) {
                    log.println(results.size() + " >= " + "fileCount: " + fileCount);
                    waitForResponses = false;
                } else {
                    log.println(results.size() + " < " + "fileCount: " + fileCount);
                }
            } catch (InterruptedException ie) {
                waitForResponses = false;
                log.println("Interrupted");
            }
        }        
    }        
    
    // TODO is this truly needed?
    private void removeOrphans() {
        // TODO check if some are null
        // -> add results required
    }
    
    @Override
    public DescriptorImpl getDescriptor() {      
        return (DescriptorImpl) super.getDescriptor();
    }
    
    public String getTask() {
        return "Protecode SC";
    }   
    
    // TODO: move to different file, this clutters
    @Extension @Symbol("protecode")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> implements ExtensionPoint {        
        @Getter @Setter private String protecodeScHost;
        @Getter @Setter private boolean dontCheckCert;
        
        public DescriptorImpl() {           
            super.load();           
        }
        
        @Override
        @SuppressWarnings("ResultOfObjectAllocationIgnored")
        public boolean configure(StaplerRequest req, JSONObject formData)
                throws Descriptor.FormException {            
            // To persist global configuration information,
            // set that to properties and call save().
            try {
                new URL(formData.getString("protecodeScHost"));
                protecodeScHost = formData.getString("protecodeScHost");
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }           
            dontCheckCert = formData.getBoolean("dontCheckCert");

            save();
            return super.configure(req, formData);
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context) {            
            // TODO Find a nice way to use this to fetch possible groups
            //  - this might be impossible in this scope
            StandardListBoxModel result = new StandardListBoxModel();
            result.withEmptySelection();
            result.withMatching(
                CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(
                    StandardUsernamePasswordCredentials.class)),
                CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class, context,
                    ACL.SYSTEM,
                    new HostnameRequirement(protecodeScHost)));
            return result;
        }

        public FormValidation doCheckProtecodeScHost(@QueryParameter String protecodeScHost)            
            throws IOException, ServletException {            
            try {
                URL protecodeHost = new URL(protecodeScHost);
                this.protecodeScHost = protecodeHost.toExternalForm();
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please provide a valid URL");
            }
        }
        
        @Override
        public String getDisplayName() {
            // TODO: give a nicer name
            // This name is such to distinguish it clearly from the old plugin (whcih I also have)
            return "New Plugin!";
        }          

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }   
    }
}