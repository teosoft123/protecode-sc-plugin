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

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.synopsys.protecode.sc.jenkins.interfaces.ProtecodeScApi;
import com.synopsys.protecode.sc.jenkins.interfaces.ProtecodeScServicesApi;
import com.synopsys.protecode.sc.jenkins.utils.UtilitiesJenkins;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.*;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ProtecodeScConnection {
  
  private static final Logger LOGGER = Logger.getLogger(ProtecodeScConnection.class.getName());
  
  /**
   * A service class containing only static functions. No point in making an object out of it. 
   */
  private ProtecodeScConnection() {
    // Don't instantiate me.
  }
  
  /**
   * Simple backend for checking the server and such. This backend doesn't use authentication. It 
   * does not have declarations for any Protecode SC API calls, only server level calls.
   * @param checkCertificate whether or not to check the server certificate.
   * @param url The URL which points to the Protecode SC instance.
   * @return the backend to use while communicating to the server with no authentication 
   */
  public static ProtecodeScServicesApi serviceBackend(URL url, boolean checkCertificate) {
    Retrofit retrofit = new Retrofit.Builder()
    .baseUrl(url.toExternalForm())
    .build();
    
    return retrofit.create(ProtecodeScServicesApi.class);
  }
  
  /**
   * Main entry point for building a backend implementation in run-time.
   * @param credentialsId the identifier for the credentials to be used.
   * @param url The url which points to the protecode-sc instance.
   * @param checkCertificate whether or not to check the server certificate.
   * @return the backend to use while communicating to the server
   */
  public static ProtecodeScApi backend(String credentialsId, URL url, boolean checkCertificate) {
    // HOW TO LOG
// Leave these here for convenience of debugging. They bleed memory _badly_ though
//    HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
//    interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
    // ... ).addInterceptor(interceptor).build();
    
    OkHttpClient okHttpClient = httpClientBuilder(checkCertificate).addInterceptor(
      (Interceptor.Chain chain) ->
      {
        Request originalRequest = chain.request();       
        
        StandardUsernamePasswordCredentials credentials 
          = UtilitiesJenkins.getCredentials(url, credentialsId);  

        // Right now we can't provide credentials "as is" to protecode so we need to extract to
        // contents
        String protecodeScUser = credentials.getUsername();
        String protecodeScPass = credentials.getPassword().toString();
        
        Request.Builder builder = originalRequest.newBuilder()
          .header(
            "Authorization",
            Credentials.basic(protecodeScUser, protecodeScPass)
          )
          .addHeader("User-Agent", Configuration.CLIENT_NAME);
        
        Request newRequest = builder.build();
        return chain.proceed(newRequest);
      }
    ).readTimeout(Configuration.TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .connectTimeout(Configuration.TIMEOUT_SECONDS, TimeUnit.SECONDS)
      //.retryOnConnectionFailure(true)
      // TODO: Evaluate if .retryOnConnectionFailure(true) should be added.
      .build();
    // TODO: Write interceptor for checking is the error 429 (too many requests) and handle that in
    // a nice fashion.
    
    
    okHttpClient.dispatcher().setMaxRequests(Configuration.MAX_REQUESTS_TO_PROTECODE);
    LOGGER.log(Level.ALL, "Max simultaneous requests to protecode limited to: {0}", 
      okHttpClient.dispatcher().getMaxRequests());
   
    Retrofit retrofit = new Retrofit.Builder()
      .baseUrl(url.toExternalForm())
      .addConverterFactory(GsonConverterFactory.create())
      .client(okHttpClient)      
      .build();
    
    return retrofit.create(ProtecodeScApi.class);
  }
  
  private static OkHttpClient.Builder httpClientBuilder(boolean checkCertificate) {
    if (checkCertificate) {
      LOGGER.log(Level.INFO, "Checking certificates");
      ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384)
        .build();
      return new OkHttpClient.Builder().connectionSpecs(Collections.singletonList(spec));
    } else {
      LOGGER.log(Level.INFO, "NOT checking certificates");
      return UnsafeOkHttpClient.getUnsafeOkHttpClient().newBuilder();
    }
  }
}
