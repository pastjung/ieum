package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애플리케이션 서비스 — 유스케이스 단위로 도메인을 조합한다.
 * 비즈니스 규칙 자체는 도메인(엔티티/VO)에, 흐름과 트랜잭션은 여기에 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserInfo signup(SignupCommand command) {
        Email email = Email.of(command.email());

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = User.builder()
                .email(email)
                .password(hash(command.password())) // TODO: Spring Security 도입 시 PasswordEncoder로 교체
                .name(command.name())
                .role(command.role())
                .build();

        return UserInfo.from(userRepository.save(user));
    }

    public UserInfo getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserInfo.from(user);
    }

    private String hash(String raw) {
        // 임시 구현 — 보안 스택 확정 전까지의 placeholder
        return "{noop}" + raw.hashCode();
    }
}
