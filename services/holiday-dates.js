/**
 * Holiday date resolution for scheduled loading phrases.
 * Resolves holiday keys to YYYY-MM-DD dates for a given year.
 */

const HOLIDAYS = [
  { key: 'new_years_day', name: "New Year's Day" },
  { key: 'valentines_day', name: "Valentine's Day" },
  { key: 'st_patricks_day', name: "St. Patrick's Day" },
  { key: 'easter', name: 'Easter' },
  { key: 'mothers_day', name: "Mother's Day" },
  { key: 'memorial_day', name: 'Memorial Day' },
  { key: 'fathers_day', name: "Father's Day" },
  { key: 'independence_day', name: 'Independence Day' },
  { key: 'labor_day', name: 'Labor Day' },
  { key: 'halloween', name: 'Halloween' },
  { key: 'veterans_day', name: 'Veterans Day' },
  { key: 'thanksgiving', name: 'Thanksgiving' },
  { key: 'christmas_eve', name: 'Christmas Eve' },
  { key: 'christmas_day', name: 'Christmas Day' },
  { key: 'new_years_eve', name: "New Year's Eve" }
];

/**
 * Computus algorithm for Easter Sunday (Anonymous Gregorian algorithm).
 */
function getEasterDate(year) {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return { month, day };
}

/**
 * Get the Nth occurrence of a weekday in a given month.
 * @param {number} year
 * @param {number} month - 1-indexed (1=Jan)
 * @param {number} weekday - 0=Sun, 1=Mon, ..., 6=Sat
 * @param {number} n - Which occurrence (1=first, -1=last)
 */
function getNthWeekday(year, month, weekday, n) {
  if (n === -1) {
    // Last occurrence: start from last day, go backwards
    const lastDay = new Date(year, month, 0).getDate(); // month is 1-indexed, Date uses 0-indexed for next month's day 0
    for (let d = lastDay; d >= 1; d--) {
      if (new Date(year, month - 1, d).getDay() === weekday) {
        return d;
      }
    }
  }
  let count = 0;
  const daysInMonth = new Date(year, month, 0).getDate();
  for (let d = 1; d <= daysInMonth; d++) {
    if (new Date(year, month - 1, d).getDay() === weekday) {
      count++;
      if (count === n) return d;
    }
  }
  return null;
}

function pad(n) {
  return String(n).padStart(2, '0');
}

/**
 * Resolve a holiday key to a YYYY-MM-DD string for a given year.
 */
function getHolidayDate(holidayKey, year) {
  switch (holidayKey) {
    case 'new_years_day':
      return `${year}-01-01`;
    case 'valentines_day':
      return `${year}-02-14`;
    case 'st_patricks_day':
      return `${year}-03-17`;
    case 'easter': {
      const e = getEasterDate(year);
      return `${year}-${pad(e.month)}-${pad(e.day)}`;
    }
    case 'mothers_day': {
      // 2nd Sunday of May
      const day = getNthWeekday(year, 5, 0, 2);
      return `${year}-05-${pad(day)}`;
    }
    case 'memorial_day': {
      // Last Monday of May
      const day = getNthWeekday(year, 5, 1, -1);
      return `${year}-05-${pad(day)}`;
    }
    case 'fathers_day': {
      // 3rd Sunday of June
      const day = getNthWeekday(year, 6, 0, 3);
      return `${year}-06-${pad(day)}`;
    }
    case 'independence_day':
      return `${year}-07-04`;
    case 'labor_day': {
      // 1st Monday of September
      const day = getNthWeekday(year, 9, 1, 1);
      return `${year}-09-${pad(day)}`;
    }
    case 'halloween':
      return `${year}-10-31`;
    case 'veterans_day':
      return `${year}-11-11`;
    case 'thanksgiving': {
      // 4th Thursday of November
      const day = getNthWeekday(year, 11, 4, 4);
      return `${year}-11-${pad(day)}`;
    }
    case 'christmas_eve':
      return `${year}-12-24`;
    case 'christmas_day':
      return `${year}-12-25`;
    case 'new_years_eve':
      return `${year}-12-31`;
    default:
      return null;
  }
}

/**
 * Check if a schedule matches a given date string (YYYY-MM-DD).
 */
function doesScheduleMatchDate(schedule, dateStr) {
  if (schedule.schedule_type === 'holiday') {
    const year = parseInt(dateStr.substring(0, 4), 10);
    const holidayDate = getHolidayDate(schedule.holiday, year);
    return holidayDate === dateStr;
  }

  if (schedule.schedule_type === 'date') {
    const scheduledDate = schedule.scheduled_date;
    if (!scheduledDate) return false;

    if (schedule.repeat_type === 'none') {
      return scheduledDate === dateStr;
    }

    if (schedule.repeat_type === 'yearly') {
      // Match month and day
      return scheduledDate.substring(5) === dateStr.substring(5);
    }

    if (schedule.repeat_type === 'monthly') {
      // Match day of month
      return scheduledDate.substring(8) === dateStr.substring(8);
    }

    if (schedule.repeat_type === 'weekly') {
      // Match day of week
      const schedDow = new Date(scheduledDate + 'T00:00:00').getDay();
      const dateDow = new Date(dateStr + 'T00:00:00').getDay();
      return schedDow === dateDow;
    }
  }

  return false;
}

/**
 * Get today's date as YYYY-MM-DD in America/Chicago (Central) timezone.
 * Ensures scheduled phrases align with users' local date, not UTC.
 */
function getTodayLocal() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/Chicago' });
}

module.exports = {
  HOLIDAYS,
  getHolidayDate,
  doesScheduleMatchDate,
  getTodayLocal
};
