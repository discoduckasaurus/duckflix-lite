/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        primary: '#1E88E5',
        'primary-dark': '#1565C0',
        accent: '#FF6F00',
      }
    },
  },
  plugins: [],
}
