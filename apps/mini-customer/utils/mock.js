const { fenYuan, slotToTime, rating, levelLabel, statusLabel } = require('./format.js')

const STORE_ID = '3100000000000000001'
const STORE2_ID = '3100000000000000002'
const LIN = '3100000000000000401'
const CHEN = '3100000000000000402'
const ZHOU = '3100000000000000403'
const P60 = '3100000000000000501'
const P45 = '3100000000000000502'
const P90 = '3100000000000000503'

const PHOTO = {
  [LIN]: '/images/therapists/lin.jpg',
  [CHEN]: '/images/therapists/chen.jpg',
  [ZHOU]: '/images/therapists/zhou.jpg',
  [STORE_ID]: '/images/stores/flagship.jpg',
  [STORE2_ID]: '/images/stores/yintai.jpg',
  neck: '/images/parts/neck.jpg',
  back: '/images/parts/back.jpg',
  arm: '/images/parts/arm.jpg',
  leg: '/images/parts/leg.jpg',
}

function therapistPhoto(id) {
  return PHOTO[String(id)] || '/images/therapists/lin.jpg'
}

function storePhoto(id) {
  return PHOTO[String(id)] || '/images/stores/flagship.jpg'
}

function partPhoto(name, id) {
  const key = String(name || '') + String(id || '')
  if (/颈|肩|头/.test(key) || id === '3100000000000000601') return PHOTO.neck
  if (/腰|背|骶/.test(key) || id === '3100000000000000602') return PHOTO.back
  if (/臂|手/.test(key) || id === '3100000000000000604') return PHOTO.arm
  if (/腿|足/.test(key) || id === '3100000000000000605') return PHOTO.leg
  return PHOTO.neck
}

const stores = [
  {
    storeId: STORE_ID,
    name: '肌松大师·演示旗舰店',
    businessStart: '10:00',
    businessEnd: '22:00',
    open: true,
    near: true,
    photo: '/images/stores/flagship.jpg',
  },
  {
    storeId: STORE2_ID,
    name: '城西银泰店',
    businessStart: '10:00',
    businessEnd: '22:00',
    open: true,
    near: false,
    photo: '/images/stores/yintai.jpg',
  },
]

const therapists = [
  {
    therapistId: LIN,
    name: '林晓',
    level: 'SENIOR',
    levelLabel: '资深技师',
    ratingX100: 490,
    rating: '4.9',
    intro: '肩颈深层，力度可调',
    photo: '/images/therapists/lin.jpg',
    homeStoreId: STORE_ID,
    tags: ['头颈肩痛', '睡眠调理'],
    symptomNames: ['头颈肩痛', '睡眠调理'],
  },
  {
    therapistId: CHEN,
    name: '陈默',
    level: 'MIDDLE',
    levelLabel: '中级技师',
    ratingX100: 480,
    rating: '4.8',
    intro: '腰背理筋',
    photo: '/images/therapists/chen.jpg',
    homeStoreId: STORE_ID,
    tags: ['腰酸背痛', '久坐劳损'],
    symptomNames: ['腰酸背痛', '久坐劳损'],
  },
  {
    therapistId: ZHOU,
    name: '周可',
    level: 'JUNIOR',
    levelLabel: '初级技师',
    ratingX100: 470,
    rating: '4.7',
    intro: '全身放松',
    photo: '/images/therapists/zhou.jpg',
    homeStoreId: STORE_ID,
    tags: ['足底疲劳', '全身放松'],
    symptomNames: ['足底疲劳', '全身放松'],
  },
]

const projects = [
  {
    projectId: P60,
    name: '全身推拿放松',
    durationMinutes: 60,
    bufferMinutes: 15,
    priceFen: 19800,
    priceYuan: '198',
  },
  {
    projectId: P45,
    name: '肩颈专项疏通',
    durationMinutes: 45,
    bufferMinutes: 15,
    priceFen: 12800,
    priceYuan: '128',
  },
  {
    projectId: P90,
    name: '腰背深层理筋',
    durationMinutes: 90,
    bufferMinutes: 15,
    priceFen: 26800,
    priceYuan: '268',
  },
]

function decorateTherapist(t) {
  return {
    ...t,
    rating: t.rating || rating(t.ratingX100),
    levelLabel: t.levelLabel || levelLabel(t.level) || '技师',
    tags: (t.symptomNames || t.tags || ['头颈肩痛', '睡眠调理']).slice(0, 2),
    photo: t.photo || therapistPhoto(t.therapistId),
  }
}

function decorateStore(s) {
  return {
    ...s,
    photo: s.photo || storePhoto(s.storeId),
  }
}

function first(list, fallback) {
  return list && list.length ? list : fallback
}

function mockAvailability({ therapistId, priceFen }) {
  const price = Number(priceFen || 19800)
  const list = therapistId
    ? therapists.filter((t) => String(t.therapistId) === String(therapistId))
    : therapists
  return {
    therapists: (list.length ? list : therapists).map((t, idx) => {
      const starts = []
      const blocks = []
      for (let slotNo = 40; slotNo < 88; slotNo += 1) {
        const start = slotToTime(slotNo)
        let state = 'FREE'
        if (slotNo >= 56 && slotNo < 64) {
          state = 'REST'
        } else if (idx === 0 && slotNo >= 78 && slotNo < 83) {
          state = 'LOCKED'
        } else if (idx === 2 && slotNo >= 40 && slotNo < 45) {
          state = 'BOOKED'
        }
        blocks.push({ slotNo, start, state })
        if (state === 'FREE' && slotNo <= 82) {
          starts.push({ slotNo, start, priceFen: price })
        }
      }
      return {
        ...decorateTherapist(t),
        starts,
        blocks,
      }
    }),
  }
}

function readOrders() {
  try {
    return wx.getStorageSync('mockOrders') || []
  } catch (e) {
    return []
  }
}

function writeOrders(list) {
  wx.setStorageSync('mockOrders', (list || []).slice(0, 20))
}

function saveOrder(order) {
  const list = readOrders().filter((o) => o.orderId !== order.orderId)
  list.unshift(order)
  writeOrders(list)
  return order
}

function mockLock({ storeId, storeName, therapistId, therapistName, projectId, projectName, date, startSlotNo, start, priceFen }) {
  const expire = new Date(Date.now() + 15 * 60 * 1000).toISOString()
  const order = {
    orderId: 'mock-' + Date.now(),
    orderNo: 'M' + String(Date.now()).slice(-8),
    status: 'PENDING_PAY',
    lockExpireAt: expire,
    payableFen: Number(priceFen || 19800),
    storeId,
    storeName,
    therapistId,
    therapistName,
    projectId,
    projectName,
    date,
    startSlotNo,
    start,
    mock: true,
  }
  return saveOrder(order)
}

function mockPay(order) {
  const paid = {
    ...order,
    status: 'BOOKED',
    mock: true,
  }
  return saveOrder(paid)
}

module.exports = {
  STORE_ID,
  stores,
  therapists,
  projects,
  decorateTherapist,
  decorateStore,
  therapistPhoto,
  storePhoto,
  partPhoto,
  first,
  mockAvailability,
  readOrders,
  saveOrder,
  mockLock,
  mockPay,
  fenYuan,
  statusLabel,
}
