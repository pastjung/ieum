package com.itda.lecture.dto;

import com.itda.lecture.media.LiveRoomGateway.IssuedMediaToken;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record MediaTokenResponse(String serverUrl, String token, String expiresAt) {

  public static MediaTokenResponse from(IssuedMediaToken issued) {
    return new MediaTokenResponse(
        issued.serverUrl(),
        issued.token(),
        UtcTimes.format(LocalDateTime.ofInstant(issued.expiresAt(), ZoneOffset.UTC)));
  }
}
