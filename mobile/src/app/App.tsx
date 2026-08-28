import React from 'react';
import { SafeAreaView, StatusBar, StyleSheet } from 'react-native';
import { HomeScreen } from '../screens/HomeScreen';

/**
 * 앱 루트. 화면이 늘어나면 src/app/navigation/ 에
 * @react-navigation/native 스택을 구성하고 여기서 감싼다.
 */
function App(): React.JSX.Element {
  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="dark-content" />
      <HomeScreen />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#ffffff' },
});

export default App;
