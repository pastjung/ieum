import React, { useState } from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';
import { userApi } from '../domains/user/api/userApi';

/**
 * 셋업 확인용 홈 화면.
 * 시각장애인 대상 앱이므로 모든 인터랙션 요소에 accessibilityLabel을 반드시 부여한다. (기능명세 3.2)
 */
export function HomeScreen(): React.JSX.Element {
  const [status, setStatus] = useState('버튼을 눌러 백엔드 연결을 확인하세요');

  async function checkServer() {
    try {
      const user = await userApi.getUser(1);
      setStatus(`서버 연결 성공: ${user.name}`);
    } catch (e) {
      setStatus(e instanceof Error ? `연결 실패: ${e.message}` : '연결 실패');
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title} accessibilityRole="header">
        잇다 — 점자 학습
      </Text>
      <Text style={styles.status} accessibilityLiveRegion="polite">
        {status}
      </Text>
      <Button
        title="서버 연결 확인"
        onPress={checkServer}
        accessibilityLabel="서버 연결 확인 버튼"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 16, padding: 24 },
  title: { fontSize: 28, fontWeight: 'bold' },
  status: { fontSize: 16, textAlign: 'center' },
});
