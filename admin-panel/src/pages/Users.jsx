import { useState, useEffect } from 'react'
import { usersApi } from '../services/api'

function Users() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [showCreateModal, setShowCreateModal] = useState(false)

  useEffect(() => {
    loadUsers()
  }, [])

  const loadUsers = async () => {
    try {
      const { data } = await usersApi.getAll()
      setUsers(data.users)
    } catch (error) {
      console.error('Failed to load users:', error)
    } finally {
      setLoading(false)
    }
  }

  const handleDelete = async (id) => {
    if (!confirm('Are you sure you want to delete this user?')) return

    try {
      await usersApi.delete(id)
      loadUsers()
    } catch (error) {
      alert('Failed to delete user')
    }
  }

  if (loading) {
    return <div className="text-center py-12">Loading...</div>
  }

  return (
    <div>
      <div className="flex justify-between items-center mb-8">
        <h2 className="text-3xl font-bold">Users</h2>
        <button
          onClick={() => setShowCreateModal(true)}
          className="px-6 py-3 bg-primary hover:bg-primary-dark rounded-lg transition"
        >
          Create User
        </button>
      </div>

      <div className="bg-slate-800 rounded-lg overflow-hidden">
        <table className="w-full">
          <thead className="bg-slate-700">
            <tr>
              <th className="px-6 py-4 text-left">Username</th>
              <th className="px-6 py-4 text-left">Role</th>
              <th className="px-6 py-4 text-left">RD Key</th>
              <th className="px-6 py-4 text-left">RD Expiry</th>
              <th className="px-6 py-4 text-left">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-700">
            {users.map(user => (
              <tr key={user.id} className="hover:bg-slate-700/50">
                <td className="px-6 py-4">{user.username}</td>
                <td className="px-6 py-4">
                  <span className={`px-2 py-1 rounded text-sm ${
                    user.isAdmin ? 'bg-purple-600' : 'bg-gray-600'
                  }`}>
                    {user.isAdmin ? 'Admin' : 'User'}
                  </span>
                </td>
                <td className="px-6 py-4 font-mono text-sm">
                  {user.rdApiKey || '—'}
                </td>
                <td className="px-6 py-4">{user.rdExpiryDate || '—'}</td>
                <td className="px-6 py-4">
                  <button
                    onClick={() => handleDelete(user.id)}
                    className="text-red-400 hover:text-red-300"
                    disabled={user.isAdmin}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showCreateModal && (
        <CreateUserModal
          onClose={() => setShowCreateModal(false)}
          onSuccess={() => {
            setShowCreateModal(false)
            loadUsers()
          }}
        />
      )}
    </div>
  )
}

function CreateUserModal({ onClose, onSuccess }) {
  const [formData, setFormData] = useState({
    username: '',
    password: '',
    isAdmin: false
  })
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)

    try {
      await usersApi.create(formData)
      onSuccess()
    } catch (error) {
      alert('Failed to create user')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-6">
      <div className="bg-slate-800 rounded-lg p-8 max-w-md w-full">
        <h3 className="text-2xl font-bold mb-6">Create User</h3>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm mb-2">Username</label>
            <input
              type="text"
              value={formData.username}
              onChange={e => setFormData({...formData, username: e.target.value})}
              className="w-full px-4 py-2 bg-slate-700 rounded-lg"
              required
            />
          </div>
          <div>
            <label className="block text-sm mb-2">Password</label>
            <input
              type="password"
              value={formData.password}
              onChange={e => setFormData({...formData, password: e.target.value})}
              className="w-full px-4 py-2 bg-slate-700 rounded-lg"
              required
            />
          </div>
          <div className="flex items-center">
            <input
              type="checkbox"
              checked={formData.isAdmin}
              onChange={e => setFormData({...formData, isAdmin: e.target.checked})}
              className="mr-2"
            />
            <label>Admin User</label>
          </div>
          <div className="flex space-x-4 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-2 bg-slate-700 rounded-lg"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex-1 py-2 bg-primary rounded-lg"
            >
              {loading ? 'Creating...' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default Users
