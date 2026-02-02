import { Link, Outlet, useLocation } from 'react-router-dom'
import { clearToken } from '../services/auth'

function Layout({ onLogout }) {
  const location = useLocation()

  const handleLogout = () => {
    clearToken()
    onLogout()
  }

  const navItems = [
    { path: '/', label: 'Dashboard' },
    { path: '/users', label: 'Users' },
    { path: '/alerts', label: 'RD Alerts' }
  ]

  return (
    <div className="min-h-screen bg-slate-900">
      {/* Header */}
      <header className="bg-slate-800 border-b border-slate-700">
        <div className="container mx-auto px-6 py-4">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-bold text-primary">DuckFlix Lite Admin</h1>
            <button
              onClick={handleLogout}
              className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded-lg transition"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      {/* Navigation */}
      <nav className="bg-slate-800 border-b border-slate-700">
        <div className="container mx-auto px-6">
          <div className="flex space-x-8">
            {navItems.map(item => (
              <Link
                key={item.path}
                to={item.path}
                className={`py-4 border-b-2 transition ${
                  location.pathname === item.path
                    ? 'border-primary text-primary'
                    : 'border-transparent text-slate-400 hover:text-slate-200'
                }`}
              >
                {item.label}
              </Link>
            ))}
          </div>
        </div>
      </nav>

      {/* Main Content */}
      <main className="container mx-auto px-6 py-8">
        <Outlet />
      </main>
    </div>
  )
}

export default Layout
