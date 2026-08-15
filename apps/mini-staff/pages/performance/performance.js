Page({
  data: {
    range: 'month',
    total: '8,240',
    clocks: '112',
  },
  pick(e) {
    const range = e.currentTarget.dataset.v
    const map = {
      day: { total: '412', clocks: '5' },
      week: { total: '2,180', clocks: '28' },
      month: { total: '8,240', clocks: '112' },
    }
    this.setData({ range, ...map[range] })
  },
  appeal() {
    wx.showModal({
      title: '提成申诉',
      content: '提成 T+1 生成，昨日 04:00 前完成。演示包记录申诉，不入账。',
      showCancel: false,
    })
  },
})
