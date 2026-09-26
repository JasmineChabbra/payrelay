import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import { Layout } from './components/Layout'
import { Overview } from './pages/Overview'
import { Payments } from './pages/Payments'
import { PaymentDetailPage } from './pages/PaymentDetail'
import { Review } from './pages/Review'
import { ChaosLab } from './pages/ChaosLab'
import { Banks } from './pages/Banks'
import { BankEditor } from './pages/BankEditor'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Overview />} />
            <Route path="payments" element={<Payments />} />
            <Route path="payments/:id" element={<PaymentDetailPage />} />
            <Route path="review" element={<Review />} />
            <Route path="banks" element={<Banks />} />
            <Route path="banks/:code" element={<BankEditor />} />
            <Route path="chaos" element={<ChaosLab />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
