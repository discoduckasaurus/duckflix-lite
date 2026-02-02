import { useState, useEffect } from 'react'
import { usersApi, alertsApi } from '../services/api'

function Dashboard() {
  const [stats, setStats] = useState({
    totalUsers: 0,
    activeAlerts: 0
  })

  useEffect(() => {
    loadStats()
  }, [])

  const loadStats = async () => {
    try {
      const [usersRes, alertsRes] = await Promise.all([
        usersApi.getAll(),
        alertsApi.getRdExpiryAlerts()
      ])

      setStats({
        totalUsers: usersRes.data.users.length,
        activeAlerts: alertsRes.data.alerts.length
      })
    } catch (error) {
      console.error('Failed to load stats:', error)
    }
  }

  return (
    <div>
      <h2 className="text-3xl font-bold mb-8">Dashboard</h2>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        <StatCard
          title="Total Users"
          value={stats.totalUsers}
          color="bg-blue-600"
        />
        <StatCard
          title="RD Expiry Alerts"
          value={stats.activeAlerts}
          color="bg-orange-600"
        />
        <StatCard
          title="Server Status"
          value="Online"
          color="bg-green-600"
        />
      </div>
    </div>
  )
}

function StatCard({ title, value, color }) {
  return (
    <div className={`${color} rounded-lg p-6 shadow-lg`}>
      <h3 className="text-sm font-medium text-white/80 mb-2">{title}</h3>
      <p className="text-4xl font-bold text-white">{value}</p>
    </div>
  )
}

export default Dashboard
