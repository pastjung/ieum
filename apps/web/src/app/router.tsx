import { createBrowserRouter } from 'react-router-dom';
import { HomePage } from '../pages/HomePage';
import { SignupPage } from '../pages/SignupPage';

export const router = createBrowserRouter([
  { path: '/', element: <HomePage /> },
  { path: '/signup', element: <SignupPage /> },
  // TODO: /rooms, /problems, /reports ...
]);
