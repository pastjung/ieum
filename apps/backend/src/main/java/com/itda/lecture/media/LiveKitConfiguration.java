package com.itda.lecture.media;

import io.livekit.server.RoomServiceClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveKitProperties.class)
class LiveKitConfiguration {

  @Bean
  LiveRoomGateway liveRoomGateway(LiveKitProperties properties) {
    return new LiveKitRoomGateway(
        properties,
        RoomServiceClient.createClient(
            properties.url(), properties.apiKey(), properties.apiSecret()),
        Clock.systemUTC());
  }
}
