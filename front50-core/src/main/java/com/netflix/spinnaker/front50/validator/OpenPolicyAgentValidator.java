/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.front50.validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.netflix.spinnaker.front50.model.pipeline.Pipeline;
import com.netflix.spinnaker.kork.web.exceptions.ValidationException;
import java.io.IOException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
public class OpenPolicyAgentValidator implements PipelineValidator {
  private static final Logger log = LoggerFactory.getLogger(OpenPolicyAgentValidator.class);
  /* define configurable variables:
    opaUrl: OPA or OPA-Proxy base url
    opaResultKey: Not needed for Proxy. The key to watch in the return from OPA.
    policyLocation: Where in OPA is the policy located, generally this is v0/location/to/policy/path
                    And for Proxy it is /v1/staticPolicy/eval
    isOpaEnabled: Policy evaluation is skipped if this is false
    isOpaProxy : true if Proxy is present instead of OPA server.
  */
  @Value("${policy.opa.url:http://oes-server-svc.oes:8085}")
  private String opaUrl;

  @Value("${policy.opa.resultKey:deny}")
  private String opaResultKey;

  @Value("${policy.opa.policyLocation:/v1/staticPolicy/eval}")
  private String opaPolicyLocation;

  @Value("${policy.opa.enabled:false}")
  private boolean isOpaEnabled;

  @Value("${policy.opa.proxy:true}")
  private boolean isOpaProxy;

  private final Gson gson = new Gson();

  /* OPA spits JSON */
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private final OkHttpClient opaClient = new OkHttpClient();

  public void validate(Pipeline pipeline, Errors errors) {
    if (!isOpaEnabled) {
      return;
    }
    String inputJson = "";
    log.debug("OPA Server: {}", this.opaUrl);
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      /* this is the input we expect from front50 when the validator is called */
      inputJson = "{\"input\": { \"pipeline\": " + objectMapper.writeValueAsString(pipeline) + "}}";
    } catch (JsonProcessingException j) {
      throw new ValidationException(j.toString(), null);
    }
    log.debug("Verifying {} with OPA", inputJson);
    /* build our request to OPA */
    RequestBody requestBody = RequestBody.create(JSON, inputJson);
    Request req =
        (new Request.Builder())
            .url(String.format("%s/%s", this.opaUrl, this.opaPolicyLocation))
            .post(requestBody)
            .build();
    try {
      /* fetch the response from the spawned call execution */
      Response httpResponse = this.opaClient.newCall(req).execute();
      ResponseBody responseBody = httpResponse.body();
      String opaStringResponse;
      if (responseBody != null) {
        opaStringResponse = responseBody.string();
      } else {
        throw new IOException("OPA call yielded null response!!");
      }
      log.info("OPA response: {}", opaStringResponse);
      if (isOpaProxy) {
        if (httpResponse.code() != 200) {
          throw new ValidationException(opaStringResponse, null);
        }
      } else {
        JsonObject opaResponse = gson.fromJson(opaStringResponse, JsonObject.class);
        JsonObject opaResult;
        if (opaResponse.has("result")) {
          opaResult = opaResponse.get("result").getAsJsonObject();
          if (opaResult.has(opaResultKey)) {
            JsonArray resultKey = opaResult.get(opaResultKey).getAsJsonArray();
            if (resultKey.size() != 0) {
              throw new ValidationException(resultKey.get(0).getAsString(), null);
            }
          } else {
            throw new ValidationException(
                "There is no '" + opaResultKey + "' field in the OPA response", null);
          }
        } else {
          throw new ValidationException("There is no 'result' field in the OPA response", null);
        }
      }
    } catch (IOException e) {
      log.error("Communication exception for OPA at {}: {}", this.opaUrl, e.toString());
      throw new ValidationException(e.toString(), null);
    }
  }
}
