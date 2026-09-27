package com.itda.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.itda.global.FastApiClient;
import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.problem.domain.Level;
import com.itda.problem.domain.Problem;
import com.itda.problem.domain.ProblemCell;
import com.itda.problem.dto.BrailleDictionaryResponse;
import com.itda.problem.dto.BrailleLookupFastApiResponse;
import com.itda.problem.repository.ProblemCellRepository;
import com.itda.problem.repository.ProblemRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class BrailleDictionaryServiceTest {

  @Mock ProblemRepository problemRepository;
  @Mock ProblemCellRepository problemCellRepository;
  @Mock FastApiClient fastApiClient;
  @InjectMocks BrailleDictionaryService service;

  @Test
  void 등록된_문제면_정답_칸을_그대로_돌려주고_AI를_부르지_않는다() {
    Problem problem = new Problem(Level.CONTRACTION, "가방");
    ReflectionTestUtils.setField(problem, "id", 31L);
    given(problemRepository.findAllByTargetTextOrderByIdAsc("가방")).willReturn(List.of(problem));
    given(problemCellRepository.findByProblemIdOrderByCellIndexAsc(31L))
        .willReturn(
            List.of(
                new ProblemCell(problem, 1, 43),
                new ProblemCell(problem, 2, 24),
                new ProblemCell(problem, 3, 54)));

    BrailleDictionaryResponse result = service.lookup(" 가방 ");

    assertThat(result.source()).isEqualTo("PROBLEM");
    assertThat(result.cells())
        .extracting(BrailleDictionaryResponse.Cell::pattern)
        .containsExactly(43, 24, 54);
    assertThat(result.braille()).isEqualTo("\u282b\u2818\u2836");
    verify(fastApiClient, never()).lookupBraille(any());
  }

  @Test
  void 없는_단어는_AI로_바꿔_요소_이름과_함께_돌려준다() {
    given(problemRepository.findAllByTargetTextOrderByIdAsc("바보")).willReturn(List.of());
    given(fastApiClient.lookupBraille(any()))
        .willReturn(
            new BrailleLookupFastApiResponse(
                "바보",
                "\u2818\u2818\u2825",
                List.of(
                    new BrailleLookupFastApiResponse.Cell(1, 24, 70L, "약자 바"),
                    new BrailleLookupFastApiResponse.Cell(2, 24, 6L, "첫소리 ㅂ"),
                    new BrailleLookupFastApiResponse.Cell(3, 37, 47L, "모음 ㅗ"))));

    BrailleDictionaryResponse result = service.lookup("바보");

    assertThat(result.source()).isEqualTo("CONVERTED");
    assertThat(result.cells())
        .extracting(BrailleDictionaryResponse.Cell::label)
        .containsExactly("약자 바", "첫소리 ㅂ", "모음 ㅗ");
    assertThat(result.braille()).isEqualTo("\u2818\u2818\u2825");
  }

  @Test
  void AI가_한글이_아니라고_거절하면_422로_알린다() {
    given(problemRepository.findAllByTargetTextOrderByIdAsc("abc")).willReturn(List.of());
    given(fastApiClient.lookupBraille(any()))
        .willThrow(
            HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_CONTENT, "", null, new byte[0], StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.lookup("abc"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BRAILLE_TEXT_UNSUPPORTED));
  }

  @Test
  void AI에_닿지_못하면_503으로_알린다() {
    given(problemRepository.findAllByTargetTextOrderByIdAsc("우유")).willReturn(List.of());
    given(fastApiClient.lookupBraille(any())).willThrow(new ResourceAccessException("down"));

    assertThatThrownBy(() -> service.lookup("우유"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BRAILLE_CONVERSION_UNAVAILABLE));
  }

  @Test
  void 빈_검색어와_너무_긴_검색어는_400() {
    assertThatThrownBy(() -> service.lookup("   "))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER));
    assertThatThrownBy(() -> service.lookup("가".repeat(41)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER));
  }
}
