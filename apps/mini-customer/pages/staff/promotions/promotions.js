Page({
  data: {
    slots: [
      { id: '1400', time: '14:00', minutes: 60, on: true },
      { id: '1530', time: '15:30', minutes: 60, on: true },
      { id: '1700', time: '17:00', minutes: 90, on: false },
    ],
    discounts: ['9.5', '9.0', '8.8', '8.5'],
    discount: '9.0',
    pay: '188',
    commission: '62.0',
    share: '21',
    picked: 2,
  },
  toggle(e) {
    const id = e.currentTarget.dataset.id
    const slots = this.data.slots.map((s) => (s.id === id ? { ...s, on: !s.on } : s))
    this.setData({ slots, picked: slots.filter((s) => s.on).length })
  },
  pickDiscount(e) {
    const discount = e.currentTarget.dataset.v
    const map = { '9.5': ['199', '65.7', '10'], '9.0': ['188', '62.0', '21'], '8.8': ['184', '60.7', '25'], '8.5': ['178', '58.7', '31'] }
    const row = map[discount] || map['9.0']
    this.setData({ discount, pay: row[0], commission: row[1], share: row[2] })
  },
  shareOld() {
    wx.showToast({ title: '演示：已发给老客', icon: 'none' })
  },
  shareCard() {
    wx.showModal({
      title: '分享卡已生成',
      content: '郑世明 明天 14:00 有空档\n头颈肩痛 60 分钟 · ' + this.data.discount + ' 折 ¥' + this.data.pay,
      showCancel: false,
    })
  },
})
