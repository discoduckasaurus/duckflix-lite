import { useState, useEffect } from 'react'
import { alertsApi } from '../services/api'

function Alerts() {
  const [alerts, setAlerts] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    loadAlerts()
  }, [])

  const loadAlerts = async () => {
    try {
      const { data } = await alertsApi.getRdExpiryAlerts()
      setAlerts(data.alerts)
    } catch (error) {
      console.error('Failed to load alerts:', error)
    } finally {
      setLoading(false)
    }
  }

  if (loading) {
    return <div className="text-center py-12">Loading...</div>
  }

  return (
    <div>
      <h2 className="text-3xl font-bold mb-8">Real-Debrid Expiry Alerts</h2>

      {alerts.length === 0 ? (
        <div className="bg-slate-800 rounded-lg p-12 text-center">
          <p className="text-slate-400">No expiring subscriptions</p>
        </div>
      ) : (
        <div className="space-y-4">
          {alerts.map((alert, index) => (
            <div
              key={index}
              className={`bg-slate-800 rounded-lg p-6 border-l-4 ${
                alert.alertType === 'expired'
                  ? 'border-red-600'
                  : alert.alertType === '1_day'
                  ? 'border-orange-600'
                  : 'border-yellow-600'
              }`}
            >
              <div className="flex justify-between items-start">
                <div>
                  <h3 className="text-xl font-semibold mb-2">{alert.username}</h3>
                  <p className="text-slate-400">
                    Expiry: {new Date(alert.rdExpiryDate).toLocaleDateString()}
                  </p>
                </div>
                <div className="text-right">
                  <span className={`px-3 py-1 rounded-full text-sm ${
                    alert.alertType === 'expired'
                      ? 'bg-red-600'
                      : alert.alertType === '1_day'
                      ? 'bg-orange-600'
                      : 'bg-yellow-600'
                  }`}>
                    {alert.daysUntilExpiry < 0
                      ? 'Expired'
                      : `${alert.daysUntilExpiry} days left`}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default Alerts
