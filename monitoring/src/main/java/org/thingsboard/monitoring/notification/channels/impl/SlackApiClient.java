/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.notification.channels.impl;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiTextResponse;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SlackApiClient {

    private final Slack slack = Slack.getInstance();
    private final String botToken;

    public SlackApiClient(String botToken) {
        this.botToken = botToken;
    }

    public String postMessage(String channelId, String text) {
        ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .build();
        ChatPostMessageResponse response = sendRequest(request);
        return response.getTs();
    }

    public String postThreadReply(String channelId, String threadTs, String text) {
        ChatPostMessageRequest request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .threadTs(threadTs)
                .build();
        ChatPostMessageResponse response = sendRequest(request);
        return response.getTs();
    }

    private ChatPostMessageResponse sendRequest(ChatPostMessageRequest request) {
        MethodsClient client = slack.methods(botToken);
        ChatPostMessageResponse response;
        try {
            response = client.chatPostMessage(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send Slack message: " + e.getMessage(), e);
        }
        checkResponse(response);
        return response;
    }

    private void checkResponse(SlackApiTextResponse response) {
        if (response.isOk()) {
            return;
        }
        String error = response.getError();
        if (error != null) {
            switch (error) {
                case "missing_scope" -> {
                    String neededScope = response.getNeeded();
                    error = "bot token scope '" + neededScope + "' is needed";
                }
                case "not_in_channel" -> error = "app needs to be added to the channel";
            }
        }
        throw new RuntimeException("Slack API error: " + error);
    }

}
