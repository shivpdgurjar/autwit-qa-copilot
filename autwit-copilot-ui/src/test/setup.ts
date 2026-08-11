import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Unmount between tests so a query in one test cannot match a leftover tree from another.
afterEach(cleanup);
